/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.module.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.automation.PolicyRejectionHandling;
import org.sliceworkz.eventmodeling.automation.PolicyStart;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * Runs the bounded context's {@link Policy policies}: one processor per policy, projecting the domain stream
 * one event at a time on a single elected leader, and handing each matching domain event to
 * {@link Reaction}, which executes the command the policy answers with.
 * <p>
 * <strong>One event at a time</strong>, because every event has an effect of its own: a batch is the unit a
 * failure rolls back, so a failing event in a larger batch would re-execute the commands of every event before
 * it in that batch on each retry. Those re-executions are harmless — the idempotency key swallows them — but
 * each one is a command execution reported to the monitoring record, once per retry round.
 * <p>
 * <strong>A stall is the processor retrying.</strong> A reaction that throws — a rejection the policy stalls on,
 * a database that is down, a bug — rolls the event back and the processor retries it with backoff, reporting
 * {@code PolicyFailed} naming the event each round, while everything behind it waits. The module remembers
 * which event each policy is stalled on, so an operator can {@link #skipStalledEvent skip} exactly that one.
 */
public class PolicyModule<DOMAIN_EVENT_TYPE> implements LifecycleCapability {

	private static final Logger LOGGER = LoggerFactory.getLogger(PolicyModule.class);

	private final String boundedContext;
	private final EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private final Instance instance;
	private final BoundedContextObserver observer;
	private final BoundedContextEventEmitter eventEmitter;
	private final ProjectorProcessorAdmin admin;
	private final Map<String, PolicyAdapter> adapters = new ConcurrentHashMap<>();
	private final Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> projectorProcessors;
	private final ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	// set once the bounded context exists, which is after this module is built; see setCapabilitiesDelegate
	private volatile CommandExecutionCapability<DOMAIN_EVENT_TYPE> executor;

	public PolicyModule ( String boundedContext, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, Collection<PolicyRegistration> policies,
			Instance instance, BoundedContextObserver observer, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		this.instance = instance;
		this.observer = observer;
		this.eventEmitter = eventEmitter;
		this.admin = new ProjectorProcessorAdmin(ProcessorKind.POLICY, boundedContext);
		this.projectorProcessors = createProjectorProcessors(policies);
		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_POLICY, projectorProcessors);
	}

	/**
	 * Hands the module what executes the commands its policies issue: the bounded context, which only
	 * exists once every module is built. Nothing reacts before {@code start()}, so nothing needs it sooner.
	 */
	public void setCapabilitiesDelegate ( CommandExecutionCapability<DOMAIN_EVENT_TYPE> executor ) {
		this.executor = executor;
	}

	/** The processors of this module that run on a single elected leader, for the leader elector: all of them. */
	public Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> leaderOnlyProcessors ( ) {
		return projectorProcessors.stream().filter(p -> p.configuredMode() == ProcessorMode.RUNNING_ON_SINGLE_LEADER).toList();
	}

	@SuppressWarnings("unchecked")
	private Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> createProjectorProcessors ( Collection<PolicyRegistration> policies ) {
		Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> result = new ArrayList<>();

		// a policy's name keys the bookmark recording how far it has reacted, its lease and the idempotency
		// keys of the commands it issues: two policies sharing it would each skip what the other advanced
		// past, and an unstable one would react to the whole stream again at every start -- see ProcessorNames
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_POLICY);

		for ( PolicyRegistration registration : policies ) {
			Policy<DOMAIN_EVENT_TYPE> policy = (Policy<DOMAIN_EVENT_TYPE>) registration.policy();
			String name = names.claim(policy, policy.policyName());
			PolicyAdapter adapter = new PolicyAdapter(policy, name, registration.onRejection());
			ProjectorProcessor<DOMAIN_EVENT_TYPE> processor = new ProjectorProcessor<>(
					ProcessorIdentification.ProcessorIdentificationBuilder
						.newBuilder(instance)
							.context(boundedContext)
							.policy()
							.name(name)
							.shared()
							.build(),
					domainEventStream,
					adapter,
					ProcessorMode.RUNNING_ON_SINGLE_LEADER,
					instance,
					policyListener(policy, name, registration));
			processor.inBatchesOf(1);
			if ( registration.start() == PolicyStart.FROM_NOW_ON ) {
				processor.startingAtHeadWhenUnbookmarked();
			}
			adapters.put(name, adapter);
			result.add(processor);
			admin.register(processor, policy.getClass().getSimpleName());
		}
		return result;
	}

	/**
	 * Turns what a policy's processor reports about itself into the policy's bounded-context events, the
	 * same set a publisher's processor emits.
	 */
	private ProjectorProcessor.ProjectorListener policyListener ( Policy<DOMAIN_EVENT_TYPE> policy, String name, PolicyRegistration registration ) {
		return new ProjectorProcessor.ProjectorListener() {

			@Override
			public void onStarted ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PolicyStarted(
							boundedContext, name, registration.start(), registration.onRejection(), eventEmitter.sliceFor(policy.getClass())));
				}
			}

			@Override
			public void onRun ( ProjectorMetrics metrics, long durationMicros ) {
				// only a run that handled something: the signal that the backlog moved, as for a publisher
				if ( eventEmitter.enabled() && metrics.eventsHandled() > 0 ) {
					BoundedContextEvent.Metrics m = new BoundedContextEvent.Metrics(durationMicros, metrics.queriesDone(), metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());
					eventEmitter.emit(new BoundedContextEvent.PolicyProcessed(
							boundedContext, name, m, eventEmitter.sliceFor(policy.getClass())));
				}
			}

			@Override
			public void onFailed ( ProjectorException failure, int consecutiveFailedRuns ) {
				if ( eventEmitter.enabled() ) {
					PolicyAdapter adapter = adapters.get(name);
					// the event the policy is stalled on: the one whose reaction threw, which the adapter
					// recorded -- the projector's own reference is the last event handed over, the same one here
					EventReference stalledOn = adapter == null || adapter.stalledOn == null
							? ( failure == null ? null : failure.getEventReference() )
							: adapter.stalledOn;
					eventEmitter.emit(new BoundedContextEvent.PolicyFailed(
							boundedContext, name,
							// the cause, not the ProjectorException wrapping it, as everywhere
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							stalledOn,
							consecutiveFailedRuns,
							eventEmitter.sliceFor(policy.getClass())));
				}
			}

			@Override
			public void onStopped ( ProjectorException failure ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PolicyStopped(
							boundedContext, name,
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							eventEmitter.sliceFor(policy.getClass()),
							BoundedContextEvent.ProcessorStopReason.FAILURE));
				}
			}

			@Override
			public void onStoppedByOperator ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PolicyStopped(
							boundedContext, name, null, null, eventEmitter.sliceFor(policy.getClass()),
							BoundedContextEvent.ProcessorStopReason.OPERATOR));
				}
			}
		};
	}

	/** The admin view over this module's processors, for {@code ProcessorAdminCapability}. */
	public List<ProcessorStatus> processorStatuses ( ) {
		return admin.statuses();
	}

	/** Restarts a stopped policy processor — see {@code ProcessorAdminCapability.restartProcessor}. */
	public boolean restartProcessor ( String name ) {
		return admin.restart(name);
	}

	/** Stops a running policy processor — see {@code ProcessorAdminCapability.stopProcessor}. */
	public boolean stopProcessor ( String name ) {
		return admin.stop(name);
	}

	/**
	 * Moves a policy past the event it is stalled on — see {@code ProcessorAdminCapability.skipStalledEvent}.
	 * The skip is taken by the policy's next attempt at the event, which is asked for at once rather than
	 * after the backoff.
	 *
	 * @return {@code true} if the policy is stalled on that event and will skip it
	 */
	public boolean skipStalledEvent ( String name, EventId event ) {
		ProjectorProcessor<?> processor = admin.processor(name); // throws naming the known ones
		PolicyAdapter adapter = adapters.get(name);
		EventReference stalledOn = adapter.stalledOn;
		if ( event == null || stalledOn == null || !stalledOn.id().equals(event) ) {
			LOGGER.info("policy '{}' is not stalled on {} (stalled on {}), nothing to skip", name, event, stalledOn);
			return false;
		}
		LOGGER.info("policy '{}' will skip {}, the event it is stalled on, on an operator's instruction", name, stalledOn);
		adapter.skipRequested.add(event);
		processor.retryNow();
		return true;
	}

	/**
	 * The projection a policy's processor runs: one {@link Reaction} per domain event.
	 */
	class PolicyAdapter implements Projection<DOMAIN_EVENT_TYPE> {

		private final Policy<DOMAIN_EVENT_TYPE> policy;
		private final String policyName;
		private final PolicyRejectionHandling onRejection;
		// the event whose reaction last threw, until a reaction completes; what an operator may skip
		volatile EventReference stalledOn;
		// the events an operator skipped while the policy was stalled on them
		final Set<EventId> skipRequested = ConcurrentHashMap.newKeySet();

		PolicyAdapter ( Policy<DOMAIN_EVENT_TYPE> policy, String policyName, PolicyRejectionHandling onRejection ) {
			this.policy = policy;
			this.policyName = policyName;
			this.onRejection = onRejection;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return policy.eventQuery();
		}

		@Override
		public void when ( Event<DOMAIN_EVENT_TYPE> event ) {
			try ( Observation.Scope<Outcome.ReactionResult> scope = observer.start(new Observation.PolicyReaction(boundedContext, policyName, event)) ) {
				try {
					if ( skipRequested.remove(event.reference().id()) ) {
						stalledOn = null;
						emitSkipped(event, BoundedContextEvent.PolicySkipReason.OPERATOR, null);
						scope.completed(new Outcome.ReactionSkipped());
						return;
					}
					if ( executor == null ) {
						throw new IllegalStateException("policy module of bounded context '%s' has no command executor yet".formatted(boundedContext));
					}
					Outcome.ReactionResult result = Reaction.react(policy, policyName, event, onRejection, instance, executor, domainEventStream);
					stalledOn = null;
					if ( result instanceof Outcome.ReactionRejected rejected ) {
						emitSkipped(event, BoundedContextEvent.PolicySkipReason.REJECTED, rejected.reason());
					}
					scope.completed(result);
				} catch ( RuntimeException | Error e ) {
					stalledOn = event.reference();
					scope.failed(e);
					throw e;
				}
			}
		}

		private void emitSkipped ( Event<DOMAIN_EVENT_TYPE> event, BoundedContextEvent.PolicySkipReason reason, String rejection ) {
			if ( eventEmitter.enabled() ) {
				eventEmitter.emit(new BoundedContextEvent.PolicyEventSkipped(
						boundedContext, policyName, event.reference(), reason, rejection, eventEmitter.sliceFor(policy.getClass())));
			}
		}
	}

	@Override
	public void start ( ) {
		this.processorThreadManager.start();
	}

	@Override
	public void stop ( ) {
		this.processorThreadManager.stop();
	}

	@Override
	public void terminate ( ) {
		this.processorThreadManager.terminate();
	}

}
