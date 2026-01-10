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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.commands.AbstractCommand;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextFunctions;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.AppendCriteria;
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
	private boolean kernelMode;
	
	private BoundedContextFunctions kernelFunctions;
	
	private Counter meterCommand;
	private Timer timerCommand;
	
	public DCBModule ( String boundedContext, Instance instance, ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, boolean kernelMode, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.readModelModule = readModelModule;
		this.domainEventStream = domainEventStream;
		this.outboundEventStream = outboundEventStream;
		this.kernelMode = kernelMode;
		
		io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
				.of("context", boundedContext);

		this.meterCommand = meterRegistry.counter("sliceworkz.eventmodeling.command.execute", tags);
		this.timerCommand = meterRegistry.timer("sliceworkz.eventmodeling.command.duration", tags);

	}
	
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return executeInternal(command, tracing, domainEventStream);
	}
	
	public Optional<EventReference>  execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, Tracing tracing ) {
		return executeInternal(command, tracing, outboundEventStream);
	}

	private <PRODUCED_EVENT_TYPE> Optional<EventReference> executeInternal ( AbstractCommand<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> command, Tracing tracing, EventStream<PRODUCED_EVENT_TYPE> targetEventStream ) {
		Optional<EventReference> result;
		
		// to avoid infinite recursing ...
		if ( kernelMode ) {
			// ... after all this code needs to execute in the end ...
			
			// execute command and get resulting events
			CommandContext<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandContext = new DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE>(boundedContext, readModelModule, domainEventStream, targetEventStream, tracing);
			CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandResult = (CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE>)command.execute(commandContext);
			
			if ( !commandResult.raisedEvents().isEmpty() ) {
				// append to the event store (with optimistic locking the DCB way)
				// and return the last event reference produced (for bookmarking purposes etc ...)
				result = targetEventStream.append(AppendCriteria.none(), commandResult.raisedEvents()).stream().reduce((first,second)->second).map(Event::reference);
				
			} else {
				LOGGER.debug("no events raised by command {}", command.getClass());
				result = Optional.empty();
			}
			
			return result;
			
		} else {

			// count the "wrapped" commands
			meterCommand.increment();
			
			return timerCommand.record(()->{
			
				@SuppressWarnings({ "unchecked", "rawtypes" })
				ExecuteCommandCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> cmd = new ExecuteCommandCommand(boundedContext, instance, readModelModule, domainEventStream, targetEventStream, command);
				kernelFunctions.executeKernelCommand(cmd, tracing);
			
				// return the last application event reference rather than the observability event 
				return cmd.getLastAppendedEventReference();
			});
		}
	}

	public void kernelFunctions ( BoundedContextFunctions kernelFunctions ) {
		this.kernelFunctions = kernelFunctions;
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