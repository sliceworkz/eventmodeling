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
package org.sliceworkz.eventmodeling.module.readmodels;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class ReadModelModule<DOMAIN_EVENT_TYPE> implements LifecycleCapability {

	private static Logger LOGGER = LoggerFactory.getLogger(ReadModelModule.class);

	private Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> projectorProcessors;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	private EventSource<DOMAIN_EVENT_TYPE> domainEventStream;
	private EventSource<Object> allInStorageEventStream;
	private Map<Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>, LiveModelInfo<DOMAIN_EVENT_TYPE>> liveModels = new HashMap<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentSharedReadModels = new ArrayList<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentLocalReadModels = new ArrayList<>();
	private String boundedContext;
	private Instance instance;

	private MeterRegistry meterRegistry;

	public record LiveModelInfo<DOMAIN_EVENT_TYPE> (
			Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass,
			SnapshotStorage<Object> snapshotStorage,
			boolean readSnapshots,
			boolean writeSnapshots,
			int snapshotEventCountThreshold,
			Counter counterSnapshotRead,
			Counter counterSnapshotWrite
		) {

		public SnapshotStorage<Object> snapshotStorageForWrite ( ) {
			return writeSnapshots ? snapshotStorage : null;
		}

	}

	public <LMSI extends LiveModelSpecificationAccessor<DOMAIN_EVENT_TYPE>> ReadModelModule (
			String boundedContext,
			EventStream<DOMAIN_EVENT_TYPE> domainEventStream,
			EventStream<Object> allInStorageEventStream,
			List<LMSI> liveModelSpecs,
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

		for ( LMSI spec : liveModelSpecs ) {
			Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass = spec.readModelClass();
			if ( this.liveModels.containsKey(readModelClass) ) {
				LOGGER.error("multiple live readmodels of type '%s' registered".formatted(readModelClass));
				throw new IllegalArgumentException("duplicate live readmodel %s".formatted(readModelClass));
			}

			io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
					.of("context", boundedContext)
					.and("readmodel", readModelClass.getSimpleName());

			Counter counterSnapshotRead = meterRegistry.counter("sliceworkz.eventmodeling.readmodel.live.snapshot.read.count", tags);
			Counter counterSnapshotWrite = meterRegistry.counter("sliceworkz.eventmodeling.readmodel.live.snapshot.write.count", tags);

			this.liveModels.put(readModelClass, new LiveModelInfo<>(
					readModelClass,
					spec.snapshotStorage(),
					spec.readSnapshots(),
					spec.writeSnapshots(),
					spec.snapshotEventCountThreshold(),
					counterSnapshotRead,
					counterSnapshotWrite));
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

		this.meterRegistry = meterRegistry;

		this.projectorProcessors = createProjectorProcessors(eventuallyConsistentSharedReadModels, eventuallyConsistentLocalReadModels, eventuallyConsistentEphemeralReadModels);
		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_READMODEL, this.projectorProcessors);

		LOGGER.info("live readmodels: %s".formatted(liveModels.keySet()));
	}

	Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> createProjectorProcessors ( Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> shared, Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> local, Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> ephemeral ) {
		Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> result = new ArrayList<>();

		shared.forEach(rm -> result.add(new ProjectorProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).shared().build(),
				(EventStream<DOMAIN_EVENT_TYPE>) domainEventStream,
				new ReadModelAdapter<>(rm, boundedContext, Storage.SHARED, meterRegistry, Tracing.actorAndChannel(rm.readmodelName(), "readmodel").instance(instance)),
				ProcessorMode.RUNNING_ON_SINGLE_LEADER,
				instance)));
		local.forEach(rm -> result.add(new ProjectorProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).local().build(),
				(EventStream<DOMAIN_EVENT_TYPE>) domainEventStream,
				new ReadModelAdapter<>(rm, boundedContext, Storage.LOCAL, meterRegistry, Tracing.actorAndChannel(rm.readmodelName(), "readmodel").instance(instance)),
				ProcessorMode.RUNNING_ON_ALL_INSTANCES,
				instance)));
		ephemeral.forEach(rm -> result.add(new ProjectorProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance).context(boundedContext).readmodel().name(rm.readmodelName()).ephemeral().build(),
				(EventStream<DOMAIN_EVENT_TYPE>) domainEventStream,
				new ReadModelAdapter<>(rm, boundedContext, Storage.EPHEMERAL, meterRegistry, Tracing.actorAndChannel(rm.readmodelName(), "readmodel").instance(instance)),
				ProcessorMode.RUNNING_ON_ALL_INSTANCES,
				instance)));

		return result;
	}

	@SuppressWarnings("unchecked")
	public <T> T liveModel ( Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... constructorParams) {
		LiveModelInfo<DOMAIN_EVENT_TYPE> info = liveModels.get(readModelClass);
		if ( info != null ) {
			io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
					.of("context", boundedContext)
					.and("readmodel", readModelClass.getSimpleName());
			meterRegistry.counter("sliceworkz.eventmodeling.readmodel.live.render", tags).increment();

			return meterRegistry.timer("sliceworkz.eventmodeling.readmodel.live.duration", tags).record(()->{
				return (T) projectLiveModel(domainEventStream, readModelClass, info, constructorParams);
			});

		} else {
			throw new IllegalArgumentException("unknown live readmodel: " + readModelClass);
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T liveModelUnbounded ( Class<? extends ReadModelWithMetaData<? extends DOMAIN_EVENT_TYPE>> readModelClass, Tracing tracing, Object... constructorParams) {
		LiveModelInfo<DOMAIN_EVENT_TYPE> info = liveModels.get(readModelClass);
		if ( info != null ) {
			io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
					.of("context", boundedContext)
					.and("readmodel", readModelClass.getSimpleName());
			meterRegistry.counter("sliceworkz.eventmodeling.readmodel.live.render", tags).increment();

			return meterRegistry.timer("sliceworkz.eventmodeling.readmodel.live.duration", tags).record(()->{
				return (T) projectLiveModel(allInStorageEventStream, readModelClass, info, constructorParams);
			});
		} else {
			throw new IllegalArgumentException("unknown live readmodel: " + readModelClass);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> projectLiveModel ( EventSource eventSource, Class readModelClass, LiveModelInfo<DOMAIN_EVENT_TYPE> info, Object[] constructorParams ) {
		long start = System.currentTimeMillis();
		try {
			ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel = (ReadModelWithMetaData<DOMAIN_EVENT_TYPE>) selectConstructor(readModelClass, constructorParams).newInstance(constructorParams);

			EventReference lastEventReference = null;

			// Load snapshot if configured and read model implements SnapshotCapable
			if ( info.readSnapshots() && readModel instanceof SnapshotCapable<?> snapshotCapable ) {
				String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
				String version = snapshotCapable.version();
				var loadedSnapshot = info.snapshotStorage().load(key, version);
				if ( loadedSnapshot.isPresent() ) {
					info.counterSnapshotRead().increment();
					((SnapshotCapable<Object>) snapshotCapable).fromSnapshot(loadedSnapshot.get().snapshot());
					lastEventReference = loadedSnapshot.get().lastEventReference();
				}
			}

			// Replay events — starting after snapshot's last event reference if available
			Projector projector = Projector.from(eventSource).towards(readModel).startingAfter(lastEventReference).build();
			ProjectorMetrics projectorMetrics = projector.run();

			// Save snapshot if configured and threshold met
			saveSnapshotIfNeeded(readModel, info, projectorMetrics, constructorParams);

			long finish = System.currentTimeMillis();
			long duration = finish - start;
			PerformanceLogger.Metrics metrics = new PerformanceLogger.Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
			PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("readmodel.live").readmodel(readModel.readmodelName()).log();

			return readModel;
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private void saveSnapshotIfNeeded ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel, LiveModelInfo<DOMAIN_EVENT_TYPE> info, ProjectorMetrics projectorMetrics, Object[] constructorParams ) {
		SnapshotStorage<Object> snapshotStorageForWrite = info.snapshotStorageForWrite();
		if ( snapshotStorageForWrite != null
				&& projectorMetrics.eventsStreamed() >= info.snapshotEventCountThreshold()
				&& readModel instanceof SnapshotCapable<?> snapshotCapable ) {
			String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
			snapshotStorageForWrite.save(key, snapshotCapable.version(), ((SnapshotCapable<Object>) snapshotCapable).takeSnapshot(), projectorMetrics.lastEventReference());
			info.counterSnapshotWrite().increment();
		}
	}

	private Constructor<?> selectConstructor ( Class<?> readModelClass, Object[] constructorParams ) {
		for ( Constructor<?> ctr : readModelClass.getDeclaredConstructors() ) {
			if ( ctr.getParameterCount() == constructorParams.length ) {
				return ctr;
			}
		}
		throw new IllegalArgumentException("no public constructor found on " + readModelClass + " for parameters " + constructorParams);
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
