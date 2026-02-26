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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateModule;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent.BoundedContextStarted;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.readmodels.UnboundedReadModelCapability;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class BoundedContextImpl<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>, UnboundedReadModelCapability<DOMAIN_EVENT_TYPE> {

	private static final Logger LOGGER = LoggerFactory.getLogger(BoundedContextImpl.class);
	
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	
	private ReadModelModule<DOMAIN_EVENT_TYPE> readmodelModule;
	private DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dcbDomainModule;
	private AggregateModule<DOMAIN_EVENT_TYPE> aggregateModule;
	private AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automationModule;
	private InboundModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> inboundModule;
	private OutboundModule<OUTBOUND_EVENT_TYPE> outboundModule;
	
	private List<? extends Slice<? extends BoundedContext<?,?,?>>> deployedFeatureSlices;
	private List<? extends Slice<? extends BoundedContext<?,?,?>>> undeployedFeatureSlices;
	
	private String name;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> domainEventCounters = new ConcurrentHashMap<>();
	private ConcurrentHashMap<String, Counter> inboundEventCounters = new ConcurrentHashMap<>();

	private AdapterRegistry adapterRegistry;

	public BoundedContextImpl (
			String name,
			List<? extends Slice<? extends BoundedContext<?,?,?>>> deployedFeatureSlices,
			List<? extends Slice<? extends BoundedContext<?,?,?>>> undeployedFeatureSlices,
			EventStream<DOMAIN_EVENT_TYPE> domainEventStream,
			EventStream<INBOUND_EVENT_TYPE> inboundEventStream,
			EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream,
			EventStream<KernelEvent> kernelLoggingEventStream,
			DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dcbModule,
			AggregateModule<DOMAIN_EVENT_TYPE> aggregateModule,
			ReadModelModule<DOMAIN_EVENT_TYPE> readmodelModule,
			AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automationModule,
			InboundModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> inboundModule,
			OutboundModule<OUTBOUND_EVENT_TYPE> outboundModule,
			Instance instance,
			MeterRegistry meterRegistry,
			AdapterRegistry adapterRegistry ) {
		this.name = name;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.deployedFeatureSlices = deployedFeatureSlices;
		this.undeployedFeatureSlices = undeployedFeatureSlices;
		
		this.domainEventStream = domainEventStream;
		
		this.readmodelModule = readmodelModule;
		this.inboundModule = inboundModule;
		this.dcbDomainModule = dcbModule;
		this.automationModule = automationModule;

		this.aggregateModule = aggregateModule;

		this.outboundModule = outboundModule;

		this.adapterRegistry = adapterRegistry;
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
	
	private Set<KernelEvent.FeatureSlice> map ( List<? extends Slice<? extends BoundedContext<?,?,?>>> featureSlices ) {
		return featureSlices.stream().map(fs->new KernelEvent.FeatureSlice(fs.name(), fs.type().name(), fs.context(), fs.chapter(), fs.tags())).collect(Collectors.toSet());
	}

	@Override
	public void start ( ) {
		LOGGER.info("starting bounded context '{}' ...", name);
		this.inboundModule.start();
		this.outboundModule.start();
		this.dcbDomainModule.start();
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
		this.automationModule.terminate();
		this.readmodelModule.terminate();
		LOGGER.info("terminating bounded context '{}'.", name);
	}
	/*
	 * LIVE MODEL CONSULTATION
	 */

	@Override
	public <T> T read(Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... params) {
		return readmodelModule.liveModel(readModelClass, tracing.instance(instance), params);
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
		return readmodelModule.liveModelUnbounded(readModelClass, tracing.instance(instance), params);
	}

	/*
	 * SYNC EVENT STORING
	 */

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event) {
		return event(event, Tracing.init(instance));
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tracing tracing ) {
		return event(event, Tags.none(), tracing.instance(instance));
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags ) {
		return event(event, tags, Tracing.init(instance));
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags, Tracing tracing ) {
		tracing = tracing.instance(instance);
		String eventName = event.getClass().getSimpleName();
		String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
		String cacheKey = eventName + ":" + channel;

		Counter counter = domainEventCounters.computeIfAbsent(cacheKey, key ->
			meterRegistry.counter("sliceworkz.eventmodeling.provided.event",
				io.micrometer.core.instrument.Tags.of("context", name, "event", eventName, "channel", channel)));
		counter.increment();

		// store event, no append criteria as we don't have any context for it
		List<? extends Event<? extends DOMAIN_EVENT_TYPE>> result = domainEventStream.append(AppendCriteria.none(), Collections.singletonList(tracing.storeOn(Event.of(event, tags))));
		return result.stream().findFirst().map(Event::reference);
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
		this.incoming ( event, null, tracing.instance(instance) );
	}

	@Override
	public void incoming(INBOUND_EVENT_TYPE event, String idempotencyKey ) {
		this.incoming ( event, idempotencyKey, Tracing.init(instance) );
	}

	@Override
	public void incoming(INBOUND_EVENT_TYPE event, String idempotencyKey, Tracing tracing ) {
		tracing = tracing.instance(instance);
		String eventName = event.getClass().getSimpleName();
		String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
		String cacheKey = eventName + ":" + channel;

		Counter counter = inboundEventCounters.computeIfAbsent(cacheKey, key ->
			meterRegistry.counter("sliceworkz.eventmodeling.inbound.event",
				io.micrometer.core.instrument.Tags.of("context", name, "event", eventName, "channel", channel)));
		counter.increment();

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
		return dcbDomainModule.execute(command, tracing.instance(instance));
	}

	@Override
	public Optional<EventReference>  execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command ) {
		return this.execute(command, Tracing.init(instance));
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tracing) {
		return dcbDomainModule.execute(command, tracing.instance(instance));
	}


	
	/*
	 * AGGREGATE SUPPORT
	 */
	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity) {
		return aggregate(aggregateClass, identity, Tracing.init(instance));
	}

	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity, Tracing tracing) {
		return aggregateModule.aggregate(aggregateClass, identity, tracing.instance(instance));
	}
	
	
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> List<T> getDeployedFeatureSlices() {
		return (List<T>)deployedFeatureSlices;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> List<T> getUndeployedFeatureSlices() {
		return (List<T>)undeployedFeatureSlices;
	}

	@Override
	public <T> T port(Class<T> portType) {
		return adapterRegistry.lookup(portType, AdapterRegistry.DEFAULT_QUALIFICATION);
	}

	@Override
	public <T> T port(Class<T> portType, String qualification) {
		return adapterRegistry.lookup(portType, qualification);
	}

}
