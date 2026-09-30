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
package org.sliceworkz.eventmodeling.module.outbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * Runs the bounded context's {@link Publisher}s: one processor per publisher, projecting the domain stream
 * on a single elected leader and handing each matching domain event to {@link Publication}, which appends
 * what the publisher publishes to the outbound stream.
 */
public class PublisherModule<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private final String boundedContext;
	private final EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private final EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;
	private final ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule;
	private final Instance instance;
	private final BoundedContextObserver observer;
	private final BoundedContextEventEmitter eventEmitter;
	private final ProjectorProcessorAdmin admin;
	private final Publication.BoundedReader<DOMAIN_EVENT_TYPE> reader;
	private final Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> projectorProcessors;
	private final ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	public PublisherModule ( String boundedContext, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream,
			ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, Collection<Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> publishers,
			Instance instance, BoundedContextObserver observer, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		this.outboundEventStream = outboundEventStream;
		this.readModelModule = readModelModule;
		this.instance = instance;
		this.observer = observer;
		this.eventEmitter = eventEmitter;
		this.admin = new ProjectorProcessorAdmin(ProcessorKind.PUBLISHER, boundedContext);
		this.reader = new Publication.BoundedReader<>() {
			@Override
			public <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R read ( Class<R> readModelClass, EventReference until, Tracing tracing, Object... params ) {
				return readModelModule.liveModelUntil(readModelClass, until, tracing, params);
			}
		};
		this.projectorProcessors = createProjectorProcessors(publishers);
		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_PUBLISHER, projectorProcessors);
	}

	/** The processors of this module that run on a single elected leader, for the leader elector: all of them. */
	public Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> leaderOnlyProcessors ( ) {
		return projectorProcessors.stream().filter(p -> p.configuredMode() == ProcessorMode.RUNNING_ON_SINGLE_LEADER).toList();
	}

	private Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> createProjectorProcessors ( Collection<Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> publishers ) {
		Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> result = new ArrayList<>();

		// a publisher's name keys the bookmark recording how far it has published the domain stream: two
		// publishers sharing it would each skip what the other advanced past, and an unstable one would
		// publish the whole domain stream again at every start -- see ProcessorNames
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_PUBLISHER);
		publishers.forEach(names::claim);

		publishers.forEach(publisher -> {
			ProjectorProcessor<DOMAIN_EVENT_TYPE> processor = new ProjectorProcessor<>(
					ProcessorIdentification.ProcessorIdentificationBuilder
						.newBuilder(instance)
							.context(boundedContext)
							.publisher()
							.name(publisher)
							.shared()
							.build(),
					domainEventStream,
					new PublisherAdapter(publisher),
					ProcessorMode.RUNNING_ON_SINGLE_LEADER,
					instance,
					publisherListener(publisher));
			result.add(processor);
			admin.register(processor, publisher.getClass().getSimpleName());
		});
		return result;
	}

	/**
	 * Turns what a publisher's processor reports about itself into the publisher's bounded-context events,
	 * the same trio a dispatcher's processor emits.
	 */
	private ProjectorProcessor.ProjectorListener publisherListener ( Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher ) {
		String name = publisher.getClass().getSimpleName();
		return new ProjectorProcessor.ProjectorListener() {

			@Override
			public void onStarted ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PublisherStarted(
							boundedContext, name, eventEmitter.sliceFor(publisher.getClass())));
				}
			}

			@Override
			public void onRun ( ProjectorMetrics metrics, long durationMicros ) {
				// only a run that handled something: the signal that the backlog moved, as for a read model
				if ( eventEmitter.enabled() && metrics.eventsHandled() > 0 ) {
					BoundedContextEvent.Metrics m = new BoundedContextEvent.Metrics(durationMicros, metrics.queriesDone(), metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());
					eventEmitter.emit(new BoundedContextEvent.PublisherProcessed(
							boundedContext, name, m, eventEmitter.sliceFor(publisher.getClass())));
				}
			}

			@Override
			public void onFailed ( ProjectorException failure, int consecutiveFailedRuns ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PublisherFailed(
							boundedContext, name,
							// the cause, not the ProjectorException wrapping it, as everywhere
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							consecutiveFailedRuns,
							eventEmitter.sliceFor(publisher.getClass())));
				}
			}

			@Override
			public void onStopped ( ProjectorException failure ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PublisherStopped(
							boundedContext, name,
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							eventEmitter.sliceFor(publisher.getClass()),
							BoundedContextEvent.ProcessorStopReason.FAILURE));
				}
			}

			@Override
			public void onStoppedByOperator ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.PublisherStopped(
							boundedContext, name, null, null, eventEmitter.sliceFor(publisher.getClass()),
							BoundedContextEvent.ProcessorStopReason.OPERATOR));
				}
			}
		};
	}

	/** The admin view over this module's processors, for {@code ProcessorAdminCapability}. */
	public List<ProcessorStatus> processorStatuses ( ) {
		return admin.statuses();
	}

	/** Restarts a stopped publisher processor — see {@code ProcessorAdminCapability.restartProcessor}. */
	public boolean restartProcessor ( String name ) {
		return admin.restart(name);
	}

	/** Stops a running publisher processor — see {@code ProcessorAdminCapability.stopProcessor}. */
	public boolean stopProcessor ( String name ) {
		return admin.stop(name);
	}

	/**
	 * The projection the publisher's processor runs: one {@link Publication} per domain event.
	 */
	class PublisherAdapter implements Projection<DOMAIN_EVENT_TYPE> {

		private final Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher;
		private final String publisherName;

		PublisherAdapter ( Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher ) {
			this.publisher = publisher;
			this.publisherName = publisher.getClass().getSimpleName();
		}

		@Override
		public EventQuery eventQuery ( ) {
			return publisher.eventQuery();
		}

		@Override
		public void when ( Event<DOMAIN_EVENT_TYPE> event ) {
			try ( Observation.Scope<Outcome.PublicationResult> scope = observer.start(new Observation.Publication(boundedContext, publisherName, event)) ) {
				try {
					// "latest" is pinned once, before anything is read, so every readLatest of this
					// publication sees the same moment; the event being published is at or before it
					EventReference head = domainEventStream.head().orElse(event.reference());
					scope.completed(Publication.publish(publisher, publisherName, event, head, instance,
							reader, outboundEventStream));
				} catch ( RuntimeException | Error e ) {
					scope.failed(e);
					throw e;
				}
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
