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
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.MeterRegistry;

public class AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {

	private static Logger LOGGER = LoggerFactory.getLogger(AutomationModule.class);

	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;

	private String boundedContext;
	private AllCapabilities<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> capabilitiesDelegate;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	private Instance instance;
	private MeterRegistry meterRegistry;
	private BoundedContextEventEmitter eventEmitter;

	public AutomationModule ( String boundedContext, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations, Instance instance, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;

		Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> aps = createAutomationProcessors(automations);

		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_AUTOMATION, aps);
	}

	public void setCapabilitiesDelegate ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> delegate ) {
		this.capabilitiesDelegate = delegate;
	}

	private AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> createAutomationContext ( Tracing tracing ) {
		return new AutomationContextImpl<>(capabilitiesDelegate, tracing);
	}
	
	Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> createAutomationProcessors ( Collection<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations ) {
		Collection<AutomationProcessor<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> result = new ArrayList<>();

		Set<String> seenNames = new HashSet<>();
		for ( Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> a : automations ) {
			String name = a.getClass().getSimpleName();
			if ( !seenNames.add(name) ) {
				LOGGER.error("duplicate automation name '%s' registered".formatted(name));
				throw new IllegalArgumentException("duplicate automation name '%s' - bookmarks would collide".formatted(name));
			}
		}

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
