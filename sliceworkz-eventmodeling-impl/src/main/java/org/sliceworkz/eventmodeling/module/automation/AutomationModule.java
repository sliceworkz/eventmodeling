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
package org.sliceworkz.eventmodeling.module.automation;

import java.util.ArrayList;
import java.util.Collection;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.MeterRegistry;

public class AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;

	private String boundedContext;
	private AllCapabilities<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> capabilitiesDelegate;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	private Instance instance;
	private MeterRegistry meterRegistry;

	public AutomationModule ( String boundedContext, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations, Instance instance, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;

		Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> aps = createAutomationProcessors(automations);

		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>("automation", aps);
	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> delegate ) {
		this.capabilitiesDelegate = delegate;
	}

	private AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> createAutomationContext ( Tracing tracing ) {
		return new AutomationContextImpl<>(capabilitiesDelegate, tracing);
	}
	
	Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> createAutomationProcessors ( Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations ) {
		Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();

		automations.forEach(a->result.add(new AutomationProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance)
					.context(boundedContext)
					.automation()
					.name(a)
					.shared()
				.build(),
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance)
					.context(boundedContext)
					.readmodel()
					.name(a.getTodoList().readmodelName()) // the one we follow
					.shared() // TODO is this always OK?  copy from readmodelprocessor?
				.build(),
				domainEventStream, this::createAutomationContext, a, AutomationProcessor.ProcessorMode.RUNNING_ON_SINGLE_LEADER, instance, boundedContext, meterRegistry))
		);
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
