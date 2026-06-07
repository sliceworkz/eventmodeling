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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.NoTranslatorRegisteredException;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
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

	private static Logger LOGGER = LoggerFactory.getLogger(InboundModule.class);

	private EventStream<INBOUND_EVENT_TYPE> inboundEventStream;

	private TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> context;

	private final List<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translators;

	private String boundedContext;
	private ProcessorThreadManager<INBOUND_EVENT_TYPE> processorThreadManager;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> translatorCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Timer> translatorTimers = new ConcurrentHashMap<>();

	public InboundModule ( String boundedContext, EventStream<INBOUND_EVENT_TYPE> inboundEventStream, Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> eventuallyConsistentTranslators, Instance instance, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.inboundEventStream = inboundEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.translators = new ArrayList<>(eventuallyConsistentTranslators);

		Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> processors = createProjectorProcessors(eventuallyConsistentTranslators);

		this.processorThreadManager = new ProcessorThreadManager<INBOUND_EVENT_TYPE>(ProcessorIdentification.TYPE_TRANSLATOR, processors);

	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> capabilities ) {
		this.context = new TranslatorContextImpl<>(capabilities);
	}

	Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> createProjectorProcessors ( Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> integrations ) {
		Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> result = new ArrayList<>();

		Set<String> seenNames = new HashSet<>();
		for ( Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> t : integrations ) {
			String name = t.getClass().getSimpleName();
			if ( !seenNames.add(name) ) {
				LOGGER.error("duplicate translator name '%s' registered".formatted(name));
				throw new IllegalArgumentException("duplicate translator name '%s' - bookmarks would collide".formatted(name));
			}
		}

		integrations.forEach(t->result.add(
				new ProjectorProcessor<>(
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
						instance)
			));
		return result;
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

			countTranslation(translatorName, eventName, channel);
			translationTimer(translatorName, eventName, channel).record(() -> translator.translate(eventWithMeta.data(), context.get()));
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

	public void incoming (INBOUND_EVENT_TYPE event, String idempotencyKey, Tracing tracing ) {
		// just append to the inbound-stream and let the projector processors do their thing...

		AppendCriteria appendCriteria = AppendCriteria.none();
		try {
			inboundEventStream.append(appendCriteria, Collections.singletonList(tracing.storeOn(Event.of(event, Tags.none()).withIdempotencyKey(idempotencyKey))));
		} catch (OptimisticLockingException e) {
			// idempotency check kicked in.  assume we already know this event
		}
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

		CapturingTranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> capturingContext = new CapturingTranslatorContext<>(context);

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
