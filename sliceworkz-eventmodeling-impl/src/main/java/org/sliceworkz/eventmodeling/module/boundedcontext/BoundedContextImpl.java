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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent.BoundedContextStarted;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.eventdispatching.ConsistentEventProcessor;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.slices.FeatureSliceConfiguration;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.MeterRegistry;

public class BoundedContextImpl<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>, ConsistentEventProcessor<DOMAIN_EVENT_TYPE>, BoundedContextFunctions {

	private static final Logger LOGGER = LoggerFactory.getLogger(BoundedContextImpl.class);
	
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	
	private ReadModelModule<DOMAIN_EVENT_TYPE> readmodelModule;
	private DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dcbDomainModule;
	private DCBModule<KernelEvent, KernelEvent> dcbKernelModule;
	private AutomationModule<DOMAIN_EVENT_TYPE> automationModule;
	private InboundModule<INBOUND_EVENT_TYPE> inboundModule;
	private OutboundModule<OUTBOUND_EVENT_TYPE> outboundModule;
	
	private List<? extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> deployedFeatureSlices;
	private List<? extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> undeployedFeatureSlices;
	
	private String name;
	private Instance instance;
	
	public BoundedContextImpl ( String name, List<? extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> deployedFeatureSlices, List<? extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> undeployedFeatureSlices, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<INBOUND_EVENT_TYPE> inboundEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, EventStream<KernelEvent> kernelLoggingEventStream, DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dcbModule, ReadModelModule<DOMAIN_EVENT_TYPE> readmodelModule, AutomationModule<DOMAIN_EVENT_TYPE> automationModule, InboundModule<INBOUND_EVENT_TYPE> inboundModule, OutboundModule<OUTBOUND_EVENT_TYPE> outboundModule, Instance instance, MeterRegistry meterRegistry ) {
		this.name = name;
		this.instance = instance;
		this.deployedFeatureSlices = deployedFeatureSlices;
		this.undeployedFeatureSlices = undeployedFeatureSlices;
		
		this.domainEventStream = domainEventStream;
		this.domainEventStream.subscribe(this::syncDomainEventHandler); // subscribe the kernel to the stream's event appends, to deliver to consistent consumers
		
		this.readmodelModule = readmodelModule;
		this.inboundModule = inboundModule;
		this.dcbDomainModule = dcbModule;
		this.automationModule = automationModule;
		
		this.dcbKernelModule = new DCBModule<KernelEvent,KernelEvent>(name, instance, null, kernelLoggingEventStream, kernelLoggingEventStream, true, meterRegistry);
		
		// pass reference to self
		this.readmodelModule.kernelFunctions(this);
		this.dcbDomainModule.kernelFunctions(this);
		
		this.outboundModule = outboundModule;
		
		this.instance = instance;

		EphemeralEvent<KernelEvent> kernelEvent = Event.of(
				new BoundedContextStarted(name, instance.logical(), instance.physical(), instance.process(), map(deployedFeatureSlices), map(undeployedFeatureSlices)), 
				Tags.none()
			);
		kernelEvent = (EphemeralEvent<KernelEvent>)Tracing.kernel(instance).storeOn(kernelEvent);

		kernelLoggingEventStream.append(
				AppendCriteria.none(), 
				Collections.singletonList(
						kernelEvent
				)
		);
	}
	
	private Set<KernelEvent.FeatureSlice> map ( List<? extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> featureSlices ) {
		return featureSlices.stream().map(fs->new KernelEvent.FeatureSlice(fs.name(), fs.type().name(), fs.context(), fs.chapter(), fs.tags())).collect(Collectors.toSet());
	}

	@Override
	public void start ( ) {
		LOGGER.info("starting bounded context '{}' ...", name);
		this.inboundModule.start();
		this.outboundModule.start();
		this.dcbDomainModule.start();
		this.dcbKernelModule.start();
		this.automationModule.start();
		this.readmodelModule.start();
		LOGGER.info("started bounded context '{}'.", name);
	}
	
	@Override
	public void stop ( ) {
		LOGGER.info("stopping bounded context '{}'...", name);
		this.inboundModule.stop();
		this.outboundModule.stop();
		this.dcbDomainModule.stop();
		this.dcbKernelModule.stop();
		this.automationModule.stop();
		this.readmodelModule.stop();
		LOGGER.info("stopped bounded context '{}'.", name);
	}
	
	@Override
	public void terminate ( ) {
		LOGGER.info("terminating bounded context '{}'...", name);
		this.inboundModule.terminate();
		this.outboundModule.terminate();
		this.dcbDomainModule.terminate();
		this.dcbKernelModule.terminate();
		this.automationModule.terminate();
		this.readmodelModule.terminate();
		LOGGER.info("terminating bounded context '{}'.", name);
	}
	/*
	 * LIVE MODEL CONSULTATION
	 */

	@Override
	public <T> T read(Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... params) {
		return readmodelModule.liveModel(readModelClass, tracing, params);
	}

	@Override
	public <T> T read(Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Object... params) {
		return readmodelModule.liveModel(readModelClass, Tracing.init(instance), params);
	}

	@Override
	public <T> T readUnbounded(Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Object... params) {
		return readmodelModule.liveModelUnbounded(readModelClass, Tracing.init(instance), params);
	}

	@Override
	public <T> T readUnbounded(Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... params) {
		return readmodelModule.liveModelUnbounded(readModelClass, tracing, params);
	}

	/*
	 * SYNC EVENT STORING
	 */

	@Override
	public Event<? extends DOMAIN_EVENT_TYPE> event(DOMAIN_EVENT_TYPE event) {
		return event(event, Tracing.init(instance));
	}

	@Override
	public Event<? extends DOMAIN_EVENT_TYPE> event(DOMAIN_EVENT_TYPE event, Tracing tracing ) {
		return event(event, Tags.none(), tracing);
	}

	@Override
	public Event<? extends DOMAIN_EVENT_TYPE> event(DOMAIN_EVENT_TYPE event, Tags tags ) {
		return event(event, tags, Tracing.init(instance));
	}

	@Override
	public Event<? extends DOMAIN_EVENT_TYPE> event(DOMAIN_EVENT_TYPE event, Tags tags, Tracing tracing ) {
		// store event, no append criteria as we don't have any context for it
		List<? extends Event<? extends DOMAIN_EVENT_TYPE>> result = domainEventStream.append(AppendCriteria.none(), Collections.singletonList(tracing.storeOn(Event.of(event, tags))));
		return result.stream().findFirst().get();
	}

	/*
	 * INBOUND INTEGRATION
	 */

	@Override
	public void incoming(INBOUND_EVENT_TYPE event) {
		this.incoming ( event, Tracing.init(instance) );
	}

	@Override
	public void incoming(INBOUND_EVENT_TYPE event, Tracing tracing ) {
		this.incoming ( event, null, tracing );
	}

	@Override
	public void incoming(INBOUND_EVENT_TYPE event, String idempotencyKey ) {
		this.incoming ( event, idempotencyKey, Tracing.init(instance) );
	}

	@Override
	public void incoming(INBOUND_EVENT_TYPE event, String idempotencyKey, Tracing tracing ) {
		inboundModule.incoming ( event, idempotencyKey, tracing );
	}

	/*
	 * COMMAND EXECUTION
	 */

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command ) {
		return execute(command, Tracing.init(instance));
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return dcbDomainModule.execute(command, tracing);
	}

	@Override
	public Optional<EventReference>  execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command ) {
		return this.execute(command, Tracing.init(instance));
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tracing) {
		return dcbDomainModule.execute(command, tracing);
	}


	
	/*
	 * KERNEL COMMAND EXECUTION
	 */
	@Override
	public Optional<EventReference> executeKernelCommand(Command<KernelEvent> kernelCommand, Tracing tracing ) {
		return dcbKernelModule.execute(kernelCommand, tracing);
	}

	/*
	 * EVENT DISPATCHING
	 */
	
	@Override
	public void syncDomainEventHandler(List<? extends Event<DOMAIN_EVENT_TYPE>> events) {
		// update consistent readmodels
		readmodelModule.updateSharedConsistentModels(events.stream());
	}

	@Override
	public <T extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> List<T> getDeployedFeatureSlices() {
		return (List<T>)deployedFeatureSlices;
	}

	@Override
	public <T extends FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> List<T> getUndeployedFeatureSlices() {
		return (List<T>)undeployedFeatureSlices;
	}

}
