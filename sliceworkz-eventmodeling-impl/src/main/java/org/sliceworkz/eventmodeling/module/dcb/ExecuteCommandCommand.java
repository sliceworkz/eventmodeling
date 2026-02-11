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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sliceworkz.eventmodeling.commands.AbstractCommand;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger.Metrics;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class ExecuteCommandCommand<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> implements Command<KernelEvent> {

	private String boundedContext;
	private Instance instance;
	private ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule;
	private AbstractCommand<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> command;
	private EventStream<DOMAIN_EVENT_TYPE> queryEventStream;
	private EventStream<PRODUCED_EVENT_TYPE> targetEventStream;
	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> domainEventCounters;

	private Optional<EventReference> lastAppendedEventReference;

	private ReadModel<DOMAIN_EVENT_TYPE> readModel;

	public ExecuteCommandCommand ( String boundedContext, Instance instance, ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, EventStream<DOMAIN_EVENT_TYPE> queryEventStream, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, AbstractCommand<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> command, MeterRegistry meterRegistry, ConcurrentHashMap<String, Counter> domainEventCounters ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.readModelModule = readModelModule;
		this.queryEventStream = queryEventStream;
		this.targetEventStream = targetEventStream;
		this.command = command;
		this.meterRegistry = meterRegistry;
		this.domainEventCounters = domainEventCounters;
	}
	
	public ReadModel<DOMAIN_EVENT_TYPE> readModel ( ) {
		return readModel;
	}
	
	@Override
	public CommandResult<KernelEvent,KernelEvent> execute(CommandContext<KernelEvent, KernelEvent> context) {
		long start = System.currentTimeMillis();
		CommandResultImpl<KernelEvent,KernelEvent> kernelCommandResult = (CommandResultImpl<KernelEvent, KernelEvent>)context.noDecisionModels();

		// execute command and get resulting events
		Tracing tracing = ((DCBCommandContextImpl<KernelEvent,KernelEvent>)context).tracing();
		DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandContext = new DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE>(boundedContext, readModelModule, queryEventStream, targetEventStream, tracing);
		CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> applicationCommandResult = (CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE>) command.execute(commandContext);

		// TODO maybe catch optimistic locking exception somewhere, and retry command with incremental backoff and logging of this fact?

		// Record metrics for each raised domain event with tracing tags
		String channel = tracing.channel() != null ? tracing.channel() : "unknown";

		// append to the event store (with optimistic locking the DCB way) and keep a reference to the last one
		this.lastAppendedEventReference =
				targetEventStream.append(applicationCommandResult.appendCriteria(), applicationCommandResult.raisedEvents())
				.stream().reduce((first,second)->second).map(Event::reference);

		for (EphemeralEvent<? extends PRODUCED_EVENT_TYPE> event : applicationCommandResult.raisedEvents()) {
			String eventName = event.data().getClass().getSimpleName();
			String cacheKey = eventName + ":" + channel;

			Counter counter = domainEventCounters.computeIfAbsent(cacheKey, key ->
				meterRegistry.counter("sliceworkz.eventmodeling.domain.event",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "event", eventName, "channel", channel, "source", "dcb")));
			counter.increment();
		}

		long finish = System.currentTimeMillis();
		long duration = finish - start;
		ProjectorMetrics projectorMetrics = commandContext.projectorMetrics();
		Metrics metrics = new Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
		PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("command.execute").command(command.commandName()).log();
		return kernelCommandResult;
	}
	
	public Optional<EventReference> getLastAppendedEventReference ( ) {
		return lastAppendedEventReference;
	}
	
}