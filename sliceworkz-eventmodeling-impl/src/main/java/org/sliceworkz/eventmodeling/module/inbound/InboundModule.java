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
package org.sliceworkz.eventmodeling.module.inbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.NoTranslatorRegisteredException;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class InboundModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private EventStream<INBOUND_EVENT_TYPE> inboundEventStream;

	private TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> context;

	private final List<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translators;

	private String boundedContext;
	private Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> projectorProcessors;
	private ProcessorThreadManager<INBOUND_EVENT_TYPE> processorThreadManager;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private BoundedContextEventEmitter eventEmitter;
	private ProjectorProcessorAdmin admin;
	private ConcurrentHashMap<String, Counter> translatorCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Timer> translatorTimers = new ConcurrentHashMap<>();

	public InboundModule ( String boundedContext, EventStream<INBOUND_EVENT_TYPE> inboundEventStream, Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> eventuallyConsistentTranslators, Instance instance, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.inboundEventStream = inboundEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;
		this.admin = new ProjectorProcessorAdmin(ProcessorKind.TRANSLATOR, boundedContext);
		this.translators = new ArrayList<>(eventuallyConsistentTranslators);

		this.projectorProcessors = createProjectorProcessors(eventuallyConsistentTranslators);

		this.processorThreadManager = new ProcessorThreadManager<INBOUND_EVENT_TYPE>(ProcessorIdentification.TYPE_TRANSLATOR, projectorProcessors);

	}

	/** The processors of this module that run on a single elected leader, for the leader elector. */
	public Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> leaderOnlyProcessors ( ) {
		return projectorProcessors.stream().filter(p -> p.configuredMode() == ProcessorMode.RUNNING_ON_SINGLE_LEADER).toList();
	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> capabilities ) {
		this.context = new TranslatorContextImpl<>(capabilities);
	}

	Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> createProjectorProcessors ( Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> integrations ) {
		Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> result = new ArrayList<>();

		// a translator's name keys the bookmark recording how far it has read the inbound stream, so it
		// has to be unique and the same on every start - see ProcessorNames
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_TRANSLATOR);
		integrations.forEach(names::claim);

		integrations.forEach(t -> {
			ProjectorProcessor<INBOUND_EVENT_TYPE> processor = new ProjectorProcessor<>(
					ProcessorIdentification.ProcessorIdentificationBuilder
						.newBuilder(instance)
							.context(boundedContext)
							.translator()
							.name(t)
							.shared()
							.build(),
					inboundEventStream,
					new TranslatorAdapter(t, ()->context, Tracing.actorAndChannel(t.getClass().getSimpleName(), "translation").instance(instance)),
					ProcessorMode.RUNNING_ON_SINGLE_LEADER,
					instance,
					translatorListener(t));
			result.add(processor);
			admin.register(processor, t.getClass().getSimpleName());
		});
		return result;
	}

	/**
	 * Turns what a translator's processor reports about itself into the bounded-context events of the
	 * translator — the same trio a read model's projector emits ({@code Started}/{@code Failed}/
	 * {@code Stopped}), which is what makes a translator that has stopped reading the inbound stream
	 * observable at all: before this the whole report was two log lines.
	 */
	private ProjectorProcessor.ProjectorListener translatorListener ( Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator ) {
		String name = translator.getClass().getSimpleName();
		return new ProjectorProcessor.ProjectorListener() {

			@Override
			public void onStarted ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.TranslatorStarted(
							boundedContext, name, eventEmitter.sliceFor(translator.getClass())));
				}
			}

			@Override
			public void onFailed ( ProjectorException failure, int consecutiveFailedRuns ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.TranslatorFailed(
							boundedContext, name,
							// the cause, not the ProjectorException wrapping it, as everywhere
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							consecutiveFailedRuns,
							eventEmitter.sliceFor(translator.getClass())));
				}
			}

			@Override
			public void onStopped ( ProjectorException failure ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.TranslatorStopped(
							boundedContext, name,
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							eventEmitter.sliceFor(translator.getClass())));
				}
			}
		};
	}

	/** The admin view over this module's processors, for {@code ProcessorAdminCapability}. */
	public List<ProcessorStatus> processorStatuses ( ) {
		return admin.statuses();
	}

	/** Restarts a stopped translator processor — see {@code ProcessorAdminCapability.restartProcessor}. */
	public boolean restartProcessor ( String name ) {
		return admin.restart(name);
	}

	class TranslatorAdapter implements Projection<INBOUND_EVENT_TYPE> {

		private Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator;
		private Supplier<TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> context;
		private Tracing tracing;
		private String translatorName;

		public TranslatorAdapter(Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator, Supplier<TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> context, Tracing tracing) {
			this.translator = translator;
			this.context = context;
			this.tracing = tracing;
			this.translatorName = translator.getClass().getSimpleName();
		}

		@Override
		public void when(Event<INBOUND_EVENT_TYPE> eventWithMeta) {
			String eventName = eventWithMeta.data().getClass().getSimpleName();
			String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;

			// one translation is one step of one flow: the tracing is derived per inbound event, and the
			// correlation id is the inbound event's when it carries one - the translation continues that
			// flow - or freshly minted when it does not (a legacy event's translation starts its own)
			Tracing eventTracing = Tracing.actorAndChannel(translatorName, "translation").instance(instance);
			String inboundCorrelationId = Tracing.readFrom(eventWithMeta).correlationId();
			if ( inboundCorrelationId != null ) {
				eventTracing = eventTracing.correlationId(inboundCorrelationId);
			}
			TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> tracedContext = new TracingTranslatorContext<>(context.get(), eventTracing);

			countTranslation(translatorName, eventName, channel);
			translationTimer(translatorName, eventName, channel).record(() -> translator.translate(eventWithMeta.data(), tracedContext));
		}

		@Override
		public EventQuery eventQuery() {
			return translator.eventQuery();
		}
	}

	private void countTranslation ( String translatorName, String eventName, String channel ) {
		String cacheKey = translatorName + ":" + eventName + ":" + channel;
		Counter counter = translatorCounters.computeIfAbsent(cacheKey, key ->
			meterRegistry.counter("sliceworkz.eventmodeling.translator.translate",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "translator", translatorName, "event", eventName, "channel", channel)));
		counter.increment();
	}

	private Timer translationTimer ( String translatorName, String eventName, String channel ) {
		String cacheKey = translatorName + ":" + eventName + ":" + channel;
		return translatorTimers.computeIfAbsent(cacheKey, key ->
			meterRegistry.timer("sliceworkz.eventmodeling.translator.duration",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "translator", translatorName, "event", eventName, "channel", channel)));
	}

	/**
	 * Appends an inbound event to the inbound stream and lets the projector processors translate it.
	 * <p>
	 * The append carries no {@link AppendCriteria}, so there is no consistency boundary to violate.
	 * De-duplication is not an exception either: storage silently ignores an event whose idempotency key
	 * it has already seen on this stream, and returns an empty result.
	 * <p>
	 * A failure to append therefore reaches the caller, which is the only party that can decide whether
	 * to retry: nothing else in this framework has a record that the event ever arrived.
	 */
	public void incoming (INBOUND_EVENT_TYPE event, String idempotencyKey, Tracing tracing ) {
		inboundEventStream.append(AppendCriteria.none(), Collections.singletonList(tracing.storeOn(Event.of(event, Tags.none()).withIdempotencyKey(idempotencyKey))));
	}

	/**
	 * Interactively translates an inbound event by synchronously running every registered translator
	 * whose {@link Translator#eventQuery()} matches the event. The inbound event is <em>not</em>
	 * appended to the inbound stream.
	 *
	 * @return references to the domain events raised during translation, in the order they were raised
	 * @throws NoTranslatorRegisteredException if no registered translator matches the event
	 */
	public List<EventReference> translate ( INBOUND_EVENT_TYPE event, Tracing tracing ) {
		String eventName = event.getClass().getSimpleName();
		String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;

		List<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> matching = translators.stream()
			.filter(t -> matches(t, event))
			.toList();

		if ( matching.isEmpty() ) {
			throw new NoTranslatorRegisteredException(
				"no translator registered for inbound event '%s' in bounded context '%s'".formatted(eventName, boundedContext));
		}

		// the caller's tracing (actor, channel, correlation id) travels onto everything the translators
		// raise, instead of being dropped at this boundary and replaced by bare instance tags
		CapturingTranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> capturingContext =
				new CapturingTranslatorContext<>(new TracingTranslatorContext<>(context, tracing));

		for ( Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator : matching ) {
			String translatorName = translator.getClass().getSimpleName();
			countTranslation(translatorName, eventName, channel);
			translationTimer(translatorName, eventName, channel).record(() -> translator.translate(event, capturingContext));
		}

		return capturingContext.references();
	}

	// matches the event - which carries no tags on this interactive path - against a translator's event query,
	// purely on event type (and any tag requirements, which a tag-less event can only satisfy when none are required).
	private boolean matches ( Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator, INBOUND_EVENT_TYPE event ) {
		EventFilter filter = translator.eventQuery().filter();
		List<EventFilterItem> items = filter.items();
		if ( items == null ) {
			return true; // null items = match all
		}
		if ( items.isEmpty() ) {
			return false; // empty items = match none
		}
		EventType type = EventType.of(event);
		return items.stream().anyMatch(item -> item.matches(type, Tags.none()));
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
