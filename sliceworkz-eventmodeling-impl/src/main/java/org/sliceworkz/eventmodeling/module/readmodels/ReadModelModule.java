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
package org.sliceworkz.eventmodeling.module.readmodels;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextFunctions;
import org.sliceworkz.eventmodeling.module.eventdispatching.EventuallyConsistentEventProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.EventuallyConsistentEventProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class ReadModelModule<DOMAIN_EVENT_TYPE> implements LifecycleCapability {
	
	private static Logger LOGGER = LoggerFactory.getLogger(ReadModelModule.class);

	private Collection<EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>> eventuallyConsistentReadModelThreadManagers;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;
	
	private BoundedContextFunctions kernelFunctions;
	private EventSource<DOMAIN_EVENT_TYPE> domainEventStream;
	private EventSource<Object> allInStorageEventStream;
	private Collection<Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>> liveModels = new ArrayList<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> consistentReadModels = new ArrayList<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentSharedReadModels = new ArrayList<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentLocalReadModels = new ArrayList<>();
	private String boundedContext;
	private Instance instance;
	
	private Counter meterLiveModel;
	private Timer timerLiveModel;
	
	
	public ReadModelModule (
			String boundedContext,
			EventStream<DOMAIN_EVENT_TYPE> domainEventStream,
			EventStream<Object> allInStorageEventStream,
			Collection<Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>> liveModelClasses, 
			Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> consistentReadModels, 
			Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentSharedReadModels, 
			Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentLocalReadModels,
			Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentEphemeralReadModels,
			Instance instance,
			MeterRegistry meterRegistry
		) {
		
		this.domainEventStream = domainEventStream;
		this.allInStorageEventStream = allInStorageEventStream;
		this.boundedContext = boundedContext;
		this.instance = instance;

		for ( Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> liveModelClass : liveModelClasses ) {
			if ( this.liveModels.contains(liveModelClass)) {
				LOGGER.error("multiple live readmodels of type '%s' registered".formatted(liveModelClass));
				throw new IllegalArgumentException("duplicate live readmodel %s".formatted(liveModelClass));
			}
			this.liveModels.add(liveModelClass);
		}

		for ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> consistentReadModel : consistentReadModels ) {
			this.consistentReadModels.add(consistentReadModel);
		}

		for ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> eventuallyConsistentSharedReadModel : eventuallyConsistentSharedReadModels ) {
			this.eventuallyConsistentSharedReadModels.add(eventuallyConsistentSharedReadModel);
		}
		for ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> eventuallyConsistentLocalReadModel : eventuallyConsistentLocalReadModels ) {
			this.eventuallyConsistentLocalReadModels.add(eventuallyConsistentLocalReadModel);
		}
		for ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> eventuallyConsistentEphemeralReadModel : eventuallyConsistentEphemeralReadModels ) {
			this.eventuallyConsistentLocalReadModels.add(eventuallyConsistentEphemeralReadModel);
		}

		this.eventuallyConsistentReadModelThreadManagers = createEventuallyConsistentEventProcessors(eventuallyConsistentSharedReadModels, eventuallyConsistentLocalReadModels, eventuallyConsistentEphemeralReadModels);
		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>("readmodel", this.eventuallyConsistentReadModelThreadManagers);
		
		io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
				.of("context", boundedContext);

		this.meterLiveModel = meterRegistry.counter("sliceworkz.eventmodeling.readmodel.live.render", tags);
		this.timerLiveModel = meterRegistry.timer("sliceworkz.eventmodeling.readmodel.live.duration", tags);
		
		LOGGER.info("live readmodels: %s".formatted(liveModelClasses));
	}

	Collection<EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>> createEventuallyConsistentEventProcessors ( Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> shared, Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> local, Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> ephemeral ) {
		Collection<EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>> result = new ArrayList<>();
		
		shared.forEach(rm->result.add(new EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>(EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).shared().build(), (EventStream<DOMAIN_EVENT_TYPE>)domainEventStream, rm.eventQuery(), rm, ProcessorMode.RUNNING_ON_SINGLE_LEADER, instance)));
		local.forEach(rm->result.add(new EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>(EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).local().build(), (EventStream<DOMAIN_EVENT_TYPE>)domainEventStream, rm.eventQuery(), rm, ProcessorMode.RUNNING_ON_ALL_INSTANCES,instance)));
		ephemeral.forEach(rm->result.add(new EventuallyConsistentEventProcessor<DOMAIN_EVENT_TYPE>(EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).ephemeral().build(), (EventStream<DOMAIN_EVENT_TYPE>)domainEventStream, rm.eventQuery(), rm, ProcessorMode.RUNNING_ON_ALL_INSTANCES,instance)));
		
		return result;
	}
	
	public void kernelFunctions ( BoundedContextFunctions kernelFunctions ) {
		this.kernelFunctions = kernelFunctions;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <T> T liveModel ( Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tx, Object... constructorParams) {
		if ( liveModels.contains(readModelClass)) {
			meterLiveModel.increment();
			
			return timerLiveModel.record(()->{
				ProjectLiveModelCommand cmd = new ProjectLiveModelCommand(domainEventStream, readModelClass, constructorParams);
				kernelFunctions.executeKernelCommand(cmd, tx);
				return (T) cmd.readModel();
			});
			
		} else {
			throw new IllegalArgumentException("unknown live readmodel: " + readModelClass);
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public <T> T liveModelUnbounded ( Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... constructorParams) {
		if ( liveModels.contains(readModelClass)) {
			meterLiveModel.increment();

			return timerLiveModel.record(()->{
				ProjectLiveModelUnboundedCommand cmd = new ProjectLiveModelUnboundedCommand(allInStorageEventStream, readModelClass, constructorParams);
				kernelFunctions.executeKernelCommand(cmd, tracing);
				return (T) cmd.readModel();
			});
		} else {
			throw new IllegalArgumentException("unknown live readmodel: " + readModelClass);
		}
	}

	/**
	 * Updates consistent readmodels shared over all BoundedContext instances (eg: database, CDN, ...)
	 * This method will only be called on a single BoundedContext instance, the one where the event is initially delivered (due to consistency)
	 */
	public void updateSharedConsistentModels ( Stream<? extends Event<DOMAIN_EVENT_TYPE>> events ) {
		events.forEach(e->{
			consistentReadModels.stream().filter(rm->rm.eventQuery().matches(e)).forEach(rm->rm.when(e));
		});
	}

	// TODO is this the way?  this can be done by the processors at start also ...
	/**
	 * Initialized all ephemeral/inmemory models before start
	 * Depending on the "local" or "shared" configuration of a read model, this method will be called on a single or all bounded context instances
	 */
	public void initializeEphemeralModels ( Stream<? extends Event<DOMAIN_EVENT_TYPE>> events ) {
		// TODO implement preloading of (inmemory) models upon kernel start
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