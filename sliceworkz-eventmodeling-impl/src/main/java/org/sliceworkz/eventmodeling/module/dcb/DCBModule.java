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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.commands.AbstractCommand;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class DCBModule<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DCBModule.class);
	
	private String boundedContext;
	private Instance instance;

	private ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule;
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;

	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> commandCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Timer> commandTimers = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Counter> domainEventCounters = new ConcurrentHashMap<>();

	public DCBModule ( String boundedContext, Instance instance, ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.readModelModule = readModelModule;
		this.domainEventStream = domainEventStream;
		this.outboundEventStream = outboundEventStream;
		this.meterRegistry = meterRegistry;
	}
	
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return executeInternal(command, tracing, domainEventStream);
	}
	
	public Optional<EventReference>  execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, Tracing tracing ) {
		return executeInternal(command, tracing, outboundEventStream);
	}

	private <PRODUCED_EVENT_TYPE> Optional<EventReference> executeInternal ( AbstractCommand<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> command, Tracing tracing, EventStream<PRODUCED_EVENT_TYPE> targetEventStream ) {
		String commandName = command.getClass().getSimpleName();

		Counter counter = commandCounters.computeIfAbsent(commandName, name ->
			meterRegistry.counter("sliceworkz.eventmodeling.command.execute",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "command", name)));
		counter.increment();

		Timer timer = commandTimers.computeIfAbsent(commandName, name ->
			meterRegistry.timer("sliceworkz.eventmodeling.command.duration",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "command", name)));

		return timer.record(() -> {
			long start = System.currentTimeMillis();

			// execute command and get resulting events
			DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandContext = new DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE>(boundedContext, readModelModule, domainEventStream, targetEventStream, tracing);
			CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandResult = (CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE>) command.execute(commandContext);

			Optional<EventReference> result;

			if ( !commandResult.raisedEvents().isEmpty() ) {
				// append to the event store (with optimistic locking the DCB way)
				// and return the last event reference produced (for bookmarking purposes etc ...)
				result = targetEventStream.append(commandResult.appendCriteria(), commandResult.raisedEvents())
						.stream().reduce((first,second)->second).map(Event::reference);

				// Record metrics for each raised domain event
				String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
				for (EphemeralEvent<? extends PRODUCED_EVENT_TYPE> event : commandResult.raisedEvents()) {
					String eventName = event.data().getClass().getSimpleName();
					String cacheKey = eventName + ":" + channel;
					Counter eventCounter = domainEventCounters.computeIfAbsent(cacheKey, key ->
						meterRegistry.counter("sliceworkz.eventmodeling.domain.event",
							io.micrometer.core.instrument.Tags.of("context", boundedContext, "event", eventName, "channel", channel, "source", "dcb")));
					eventCounter.increment();
				}
			} else {
				LOGGER.debug("no events raised by command {}", command.getClass());
				result = Optional.empty();
			}

			long finish = System.currentTimeMillis();
			long duration = finish - start;
			ProjectorMetrics projectorMetrics = commandContext.projectorMetrics();
			PerformanceLogger.Metrics metrics = new PerformanceLogger.Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
			PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("command.execute").command(command.commandName()).log();

			return result;
		});
	}
	
	@Override
	public void start ( ) {

	}

	@Override
	public void stop ( ) {

	}
	
	@Override
	public void terminate ( ) {
	}

}