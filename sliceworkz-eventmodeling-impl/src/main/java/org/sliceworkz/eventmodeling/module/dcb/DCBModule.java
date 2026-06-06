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
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
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

	private final BoundedContextEventEmitter eventEmitter;

	public DCBModule ( String boundedContext, Instance instance, ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.readModelModule = readModelModule;
		this.domainEventStream = domainEventStream;
		this.outboundEventStream = outboundEventStream;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;
	}
	
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return executeAbstractCommand(command, command.commandName(), tracing, domainEventStream, null);
	}

	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeAbstractCommand(command, command.commandName(), tracing, domainEventStream, idempotencyKey);
	}

	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, Tracing tracing ) {
		return executeAbstractCommand(command, command.commandName(), tracing, outboundEventStream, null);
	}

	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeAbstractCommand(command, command.commandName(), tracing, outboundEventStream, idempotencyKey);
	}

	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing ) {
		return executeCommandWithResult(command, tracing, null);
	}

	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeCommandWithResult(command, tracing, idempotencyKey);
	}

	private <PRODUCED_EVENT_TYPE> Optional<EventReference> executeAbstractCommand ( AbstractCommand<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> command, String commandName, Tracing tracing, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, String idempotencyKey ) {
		return timed(commandName, () -> {
			long start = System.currentTimeMillis();

			Tracing tracingWithCommand = tracing.command(commandName);
			DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandContext = new DCBCommandContextImpl<>(boundedContext, readModelModule, domainEventStream, targetEventStream, tracingWithCommand);
			command.execute(commandContext);
			CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandResult = commandContext.getCommandResult();

			Optional<EventReference> eventReference = persistAndRecord(commandResult, targetEventStream, commandName, idempotencyKey, tracingWithCommand);

			logPerformance(commandContext, commandName, command.getClass(), start);

			return eventReference;
		});
	}

	private <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeCommandWithResult ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing, String idempotencyKey ) {
		String commandName = command.commandName();
		return timed(commandName, () -> {
			long start = System.currentTimeMillis();

			Tracing tracingWithCommand = tracing.command(commandName);
			DCBCommandContextImpl<DOMAIN_EVENT_TYPE,DOMAIN_EVENT_TYPE> commandContext = new DCBCommandContextImpl<>(boundedContext, readModelModule, domainEventStream, domainEventStream, tracingWithCommand);
			RESPONSE_TYPE response = command.execute(commandContext);
			CommandResultImpl<DOMAIN_EVENT_TYPE,DOMAIN_EVENT_TYPE> commandResult = commandContext.getCommandResult();

			Optional<EventReference> eventReference = persistAndRecord(commandResult, domainEventStream, commandName, idempotencyKey, tracingWithCommand);

			logPerformance(commandContext, commandName, command.getClass(), start);

			return new CommandExecutionResult<>(eventReference, response);
		});
	}

	private <PRODUCED_EVENT_TYPE> Optional<EventReference> persistAndRecord ( CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> commandResult, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, String commandName, String idempotencyKey, Tracing tracing ) {
		// resolve and apply idempotency key (internal strategy vs external key)
		String resolvedKey = commandResult.resolveIdempotencyKey(idempotencyKey);
		if ( resolvedKey != null ) {
			commandResult.applyIdempotencyKey(resolvedKey);
		}

		if ( !commandResult.raisedEvents().isEmpty() ) {
			// append to the event store (with optimistic locking the DCB way)
			// and return the last event reference produced (for bookmarking purposes etc ...)
			Optional<EventReference> result = targetEventStream.append(commandResult.appendCriteria(), commandResult.raisedEvents())
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

			return result;
		} else {
			LOGGER.debug("no events raised by command {}", commandName);
			return Optional.empty();
		}
	}

	private <T> T timed ( String commandName, java.util.function.Supplier<T> action ) {
		Counter counter = commandCounters.computeIfAbsent(commandName, name ->
			meterRegistry.counter("sliceworkz.eventmodeling.command.execute",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "command", name)));
		counter.increment();

		Timer timer = commandTimers.computeIfAbsent(commandName, name ->
			meterRegistry.timer("sliceworkz.eventmodeling.command.duration",
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "command", name)));

		return timer.record(action);
	}

	private void logPerformance ( DCBCommandContextImpl<?,?> commandContext, String commandName, Class<?> commandClass, long start ) {
		if ( !eventEmitter.enabled() ) {
			return;
		}
		long finish = System.currentTimeMillis();
		long duration = finish - start;
		ProjectorMetrics projectorMetrics = commandContext.projectorMetrics();
		BoundedContextEvent.Metrics metrics = new BoundedContextEvent.Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
		eventEmitter.emit(new BoundedContextEvent.CommandExecuted(boundedContext, commandName, metrics, eventEmitter.sliceFor(commandClass)), commandContext.tracing());
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