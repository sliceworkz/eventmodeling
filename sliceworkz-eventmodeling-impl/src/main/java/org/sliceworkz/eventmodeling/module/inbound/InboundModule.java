/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
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

		Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> processors = createProjectorProcessors(eventuallyConsistentTranslators);

		this.processorThreadManager = new ProcessorThreadManager<INBOUND_EVENT_TYPE>("translator", processors);

	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> capabilities ) {
		this.context = new TranslatorContextImpl<>(capabilities);
	}

	Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> createProjectorProcessors ( Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> integrations ) {
		Collection<ProjectorProcessor<INBOUND_EVENT_TYPE>> result = new ArrayList<>();

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
			String channel = tracing.channel() != null ? tracing.channel() : "unknown";
			String cacheKey = translatorName + ":" + eventName + ":" + channel;

			Counter counter = translatorCounters.computeIfAbsent(cacheKey, key ->
				meterRegistry.counter("sliceworkz.eventmodeling.translator.translate",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "translator", translatorName, "event", eventName, "channel", channel)));
			counter.increment();

			Timer timer = translatorTimers.computeIfAbsent(cacheKey, key ->
				meterRegistry.timer("sliceworkz.eventmodeling.translator.duration",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "translator", translatorName, "event", eventName, "channel", channel)));

			timer.record(() -> translator.translate(eventWithMeta.data(), context.get()));
		}

		@Override
		public EventQuery eventQuery() {
			return translator.eventQuery();
		}
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
