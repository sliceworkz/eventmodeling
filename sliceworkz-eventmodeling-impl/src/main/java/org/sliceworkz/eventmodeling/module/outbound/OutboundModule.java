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

import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.eventdispatching.EventuallyConsistentEventProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.EventuallyConsistentEventProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.stream.EventStream;

public class OutboundModule<OUTBOUND_EVENT_TYPE> implements LifecycleCapability {
	
	private EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;
	
	private String boundedContext;
	private ProcessorThreadManager<OUTBOUND_EVENT_TYPE> processorThreadManager;
	private Instance instance;
	
	public OutboundModule ( String boundedContext, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers, Instance instance ) {
		this.boundedContext = boundedContext;
		this.outboundEventStream = outboundEventStream;
		this.instance = instance;
		
		Collection<EventuallyConsistentEventProcessor<OUTBOUND_EVENT_TYPE>> eceps = createEventuallyConsistentEventProcessors(dispatchers);
		
		this.processorThreadManager = new ProcessorThreadManager<OUTBOUND_EVENT_TYPE>("dispatcher", eceps);
	}
	
	Collection<EventuallyConsistentEventProcessor<OUTBOUND_EVENT_TYPE>> createEventuallyConsistentEventProcessors ( Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers ) {
		Collection<EventuallyConsistentEventProcessor<OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();
		
		dispatchers.forEach(t->result.add(new EventuallyConsistentEventProcessor<OUTBOUND_EVENT_TYPE>(EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).dispatcher().name(t).shared().build(), (EventStream<OUTBOUND_EVENT_TYPE>)outboundEventStream, t.eventQuery(), t, ProcessorMode.RUNNING_ON_SINGLE_LEADER, instance)));
		return result;
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
