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
package org.sliceworkz.eventmodeling.module.automation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.MeterRegistry;

public class AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;

	private String boundedContext;
	private AllCapabilities<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> capabilitiesDelegate;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;
	private final List<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationProcessors;

	private Instance instance;
	private MeterRegistry meterRegistry;
	private BoundedContextEventEmitter eventEmitter;

	public AutomationModule ( String boundedContext, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations, Instance instance, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;

		this.automationProcessors = createAutomationProcessors(automations);

		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_AUTOMATION, automationProcessors);
	}

	/**
	 * The state of every automation on this instance, in registration order.
	 *
	 * @see org.sliceworkz.eventmodeling.automation.AutomationAdminCapability#automations()
	 */
	public List<AutomationStatus> automations ( ) {
		return automationProcessors.stream().map(AutomationProcessor::status).toList();
	}

	/**
	 * Restarts a stopped automation on this instance.
	 *
	 * @see org.sliceworkz.eventmodeling.automation.AutomationAdminCapability#restartAutomation(String)
	 */
	public boolean restartAutomation ( String automation ) {
		return automationProcessors.stream()
				.filter(p -> p.automationId().equals(automation))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("no automation '%s' is registered on bounded context '%s', known are %s".formatted(
						automation, boundedContext, automationProcessors.stream().map(AutomationProcessor::automationId).toList())))
				.restart();
	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> delegate ) {
		this.capabilitiesDelegate = delegate;
	}

	private AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> createAutomationContext ( Tracing tracing ) {
		return new AutomationContextImpl<>(capabilitiesDelegate, tracing);
	}
	
	List<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> createAutomationProcessors ( Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations ) {
		List<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();

		// An automation's simple name is its identity: it keys the bookmark that records how far it has
		// got, the metric tags, and the id AutomationAdminCapability addresses it by - see ProcessorNames
		// for what a duplicate or an unstable one costs.
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_AUTOMATION);
		automations.forEach(names::claim);

		automations.forEach(a->result.add(new AutomationProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance)
					.context(boundedContext)
					.automation()
					.name(a)
					.shared()
				.build(),
				// Must mirror the storage class the projector chose for this read model
				// (see ReadModelModule.createProjectorProcessors); otherwise the automation
				// watches a bookmark that nobody writes and handle() never fires. Both sides
				// derive it from the todo list itself, so they cannot drift apart.
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance)
					.context(boundedContext)
					.readmodel()
					.name(a.getTodoList().readmodelName())
					.storage(a.getTodoList().storage())
				.build(),
				domainEventStream, this::createAutomationContext, a, AutomationProcessor.ProcessorMode.RUNNING_ON_SINGLE_LEADER, instance, boundedContext, meterRegistry, eventEmitter))
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
