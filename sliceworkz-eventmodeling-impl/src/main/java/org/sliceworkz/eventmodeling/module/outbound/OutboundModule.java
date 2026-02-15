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
package org.sliceworkz.eventmodeling.module.outbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

public class OutboundModule<OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;

	private String boundedContext;
	private ProcessorThreadManager<OUTBOUND_EVENT_TYPE> processorThreadManager;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> dispatcherCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Timer> dispatcherTimers = new ConcurrentHashMap<>();

	public OutboundModule ( String boundedContext, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers, Instance instance, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.outboundEventStream = outboundEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;

		Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> processors = createProjectorProcessors(dispatchers);

		this.processorThreadManager = new ProcessorThreadManager<OUTBOUND_EVENT_TYPE>("dispatcher", processors);
	}

	Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> createProjectorProcessors ( Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers ) {
		Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();

		dispatchers.forEach(t->result.add(
				new ProjectorProcessor<>(
						EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder
							.newBuilder(instance)
								.context(boundedContext)
								.dispatcher()
								.name(t)
								.shared()
								.build(),
						(EventStream<OUTBOUND_EVENT_TYPE>)outboundEventStream,
						new DispatcherAdapter(t, Tracing.actorAndChannel(t.getClass().getSimpleName(), "dispatch").instance(instance)),
						ProcessorMode.RUNNING_ON_SINGLE_LEADER,
						instance)));
		return result;
	}

	class DispatcherAdapter implements Projection<OUTBOUND_EVENT_TYPE> {

		private Dispatcher<OUTBOUND_EVENT_TYPE> dispatcher;
		private String dispatcherName;
		private Tracing tracing;

		public DispatcherAdapter(Dispatcher<OUTBOUND_EVENT_TYPE> dispatcher, Tracing tracing) {
			this.dispatcher = dispatcher;
			this.dispatcherName = dispatcher.getClass().getSimpleName();
			this.tracing = tracing;
		}

		@Override
		public void when(Event<OUTBOUND_EVENT_TYPE> eventWithMeta) {
			String eventName = eventWithMeta.data().getClass().getSimpleName();
			String channel = tracing.channel() != null ? tracing.channel() : "unknown";
			String cacheKey = dispatcherName + ":" + eventName + ":" + channel;

			Counter counter = dispatcherCounters.computeIfAbsent(cacheKey, key ->
				meterRegistry.counter("sliceworkz.eventmodeling.dispatcher.dispatch",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "dispatcher", dispatcherName, "event", eventName, "channel", channel)));
			counter.increment();

			Timer timer = dispatcherTimers.computeIfAbsent(cacheKey, key ->
				meterRegistry.timer("sliceworkz.eventmodeling.dispatcher.duration",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "dispatcher", dispatcherName, "event", eventName, "channel", channel)));

			timer.record(() -> dispatcher.when(eventWithMeta));
		}

		@Override
		public EventQuery eventQuery() {
			return dispatcher.eventQuery();
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
