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
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

public class OutboundModule<OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;

	private String boundedContext;
	private Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> projectorProcessors;
	private ProcessorThreadManager<OUTBOUND_EVENT_TYPE> processorThreadManager;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private BoundedContextEventEmitter eventEmitter;
	private ProjectorProcessorAdmin admin;
	private ConcurrentHashMap<String, Counter> dispatcherCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Timer> dispatcherTimers = new ConcurrentHashMap<>();

	public OutboundModule ( String boundedContext, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers, Instance instance, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.outboundEventStream = outboundEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;
		this.admin = new ProjectorProcessorAdmin(ProcessorKind.DISPATCHER, boundedContext);

		this.projectorProcessors = createProjectorProcessors(dispatchers);

		this.processorThreadManager = new ProcessorThreadManager<OUTBOUND_EVENT_TYPE>(ProcessorIdentification.TYPE_DISPATCHER, projectorProcessors);
	}

	/** The processors of this module that run on a single elected leader, for the leader elector. */
	public Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> leaderOnlyProcessors ( ) {
		return projectorProcessors.stream().filter(p -> p.configuredMode() == ProcessorMode.RUNNING_ON_SINGLE_LEADER).toList();
	}

	Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> createProjectorProcessors ( Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers ) {
		Collection<ProjectorProcessor<OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();

		// A dispatcher's name keys the bookmark recording what it has published, so the stakes here are
		// higher than anywhere else: two dispatchers sharing a name share that bookmark and each skips
		// what the other advanced past, so events are silently never published; an unstable name gives a
		// fresh bookmark on every start, so the whole outbound stream is published again at every boot.
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_DISPATCHER);
		dispatchers.forEach(names::claim);

		dispatchers.forEach(t -> {
			ProjectorProcessor<OUTBOUND_EVENT_TYPE> processor = new ProjectorProcessor<>(
					ProcessorIdentification.ProcessorIdentificationBuilder
						.newBuilder(instance)
							.context(boundedContext)
							.dispatcher()
							.name(t)
							.shared()
							.build(),
					(EventStream<OUTBOUND_EVENT_TYPE>)outboundEventStream,
					new DispatcherAdapter(t, Tracing.actorAndChannel(t.getClass().getSimpleName(), "dispatch").instance(instance)),
					ProcessorMode.RUNNING_ON_SINGLE_LEADER,
					instance,
					dispatcherListener(t));
			result.add(processor);
			admin.register(processor, t.getClass().getSimpleName());
		});
		return result;
	}

	/**
	 * Turns what a dispatcher's processor reports about itself into the bounded-context events of the
	 * dispatcher — the same trio a read model's projector emits ({@code Started}/{@code Failed}/
	 * {@code Stopped}). This is the reporting that matters most of the three kinds: a dispatcher is
	 * the only thing publishing the outbound stream, so its processor being down is deployment-wide
	 * silence toward an external system, and before this the whole report was two log lines.
	 */
	private ProjectorProcessor.ProjectorListener dispatcherListener ( Dispatcher<OUTBOUND_EVENT_TYPE> dispatcher ) {
		String name = dispatcher.getClass().getSimpleName();
		return new ProjectorProcessor.ProjectorListener() {

			@Override
			public void onStarted ( ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.DispatcherStarted(
							boundedContext, name, eventEmitter.sliceFor(dispatcher.getClass())));
				}
			}

			@Override
			public void onFailed ( ProjectorException failure, int consecutiveFailedRuns ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.DispatcherFailed(
							boundedContext, name,
							// the cause, not the ProjectorException wrapping it, as everywhere
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							consecutiveFailedRuns,
							eventEmitter.sliceFor(dispatcher.getClass())));
				}
			}

			@Override
			public void onStopped ( ProjectorException failure ) {
				if ( eventEmitter.enabled() ) {
					eventEmitter.emit(new BoundedContextEvent.DispatcherStopped(
							boundedContext, name,
							BoundedContextEvent.Failure.of(failure == null ? null : failure.getCause()),
							failure == null ? null : failure.getEventReference(),
							eventEmitter.sliceFor(dispatcher.getClass())));
				}
			}
		};
	}

	/** The admin view over this module's processors, for {@code ProcessorAdminCapability}. */
	public List<ProcessorStatus> processorStatuses ( ) {
		return admin.statuses();
	}

	/** Restarts a stopped dispatcher processor — see {@code ProcessorAdminCapability.restartProcessor}. */
	public boolean restartProcessor ( String name ) {
		return admin.restart(name);
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
			String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
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
