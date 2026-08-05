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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.module.threading.ProcessorNames;
import org.sliceworkz.eventmodeling.module.threading.ProcessorThreadManager;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class ReadModelModule<DOMAIN_EVENT_TYPE> implements LifecycleCapability {

	private static Logger LOGGER = LoggerFactory.getLogger(ReadModelModule.class);

	static final String EPHEMERAL_PROJECTION_TIMEOUT_PROPERTY = "sliceworkz.eventmodeling.readmodel.ephemeral.projection.timeout.ms";
	static final long DEFAULT_EPHEMERAL_PROJECTION_TIMEOUT_MS = 300000; // 5 minutes
	private static final long EPHEMERAL_PROJECTION_TIMEOUT_MS = Long.getLong(EPHEMERAL_PROJECTION_TIMEOUT_PROPERTY, DEFAULT_EPHEMERAL_PROJECTION_TIMEOUT_MS);
	private static final long PROGRESS_LOG_INTERVAL_MS = 10000;

	private Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> projectorProcessors;
	private ProcessorThreadManager<DOMAIN_EVENT_TYPE> processorThreadManager;

	private EventSource<DOMAIN_EVENT_TYPE> domainEventStream;
	private EventSource<Object> allInStorageEventStream;
	private Map<Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>, LiveModelInfo<DOMAIN_EVENT_TYPE>> liveModels = new HashMap<>();
	private Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentReadModels = new ArrayList<>();
	private String boundedContext;
	private Instance instance;

	private MeterRegistry meterRegistry;
	private BoundedContextEventEmitter eventEmitter;

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

	public <LMSI extends LiveModelSpecificationAccessor> ReadModelModule (
			String boundedContext,
			EventStream<DOMAIN_EVENT_TYPE> domainEventStream,
			EventStream<Object> allInStorageEventStream,
			List<LMSI> liveModelSpecs,
			Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentReadModels,
			Instance instance,
			MeterRegistry meterRegistry,
			BoundedContextEventEmitter eventEmitter
		) {

		this.domainEventStream = domainEventStream;
		this.allInStorageEventStream = allInStorageEventStream;
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.eventEmitter = eventEmitter;

		for ( LMSI spec : liveModelSpecs ) {
			@SuppressWarnings("unchecked")
			Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass = (Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>) (Class<?>) spec.readModelClass();
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

		// the name keys this read model's bookmark, and readmodelName() defaults to the simple class name
		// — see ProcessorNames for what a duplicate or an unstable one costs. Live models key no bookmark
		// of their own, but they share the name space a read is addressed by, so they are reserved.
		ProcessorNames names = ProcessorNames.of(ProcessorIdentification.TYPE_READMODEL);
		for ( Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> liveClass : this.liveModels.keySet() ) {
			names.reserve(liveClass.getSimpleName());
		}

		for ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> eventuallyConsistentReadModel : eventuallyConsistentReadModels ) {
			names.claim(eventuallyConsistentReadModel, eventuallyConsistentReadModel.readmodelName());
			this.eventuallyConsistentReadModels.add(eventuallyConsistentReadModel);
		}

		this.meterRegistry = meterRegistry;

		this.projectorProcessors = createProjectorProcessors(this.eventuallyConsistentReadModels);
		this.processorThreadManager = new ProcessorThreadManager<DOMAIN_EVENT_TYPE>(ProcessorIdentification.TYPE_READMODEL, this.projectorProcessors);

		LOGGER.info("live readmodels: %s".formatted(liveModels.keySet()));
	}

	Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> createProjectorProcessors ( Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModels ) {
		Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> result = new ArrayList<>();

		readModels.forEach(rm -> {
			ReadModelStorage readModelStorage = rm.storage();
			Storage storage = Storage.of(readModelStorage);
			result.add(new ProjectorProcessor<>(
				ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(instance)
					.context(boundedContext).readmodel().name(rm.readmodelName())
					.storage(readModelStorage)
					.build(),
				(EventStream<DOMAIN_EVENT_TYPE>) domainEventStream,
				new ReadModelAdapter<>(rm, boundedContext, storage, meterRegistry, Tracing.actorAndChannel(rm.readmodelName(), "readmodel").instance(instance)),
				processorModeFor(readModelStorage),
				instance,
				ecRunListener(rm, storage)));
		});

		return result;
	}

	/**
	 * Only shared state is written once, so only a shared read model is projected by a single elected
	 * leader. Ephemeral and local state lives per instance and has to be projected by each of them —
	 * projecting those on the leader alone would leave every other instance answering reads from a
	 * model nobody ever filled.
	 */
	static ProcessorMode processorModeFor ( ReadModelStorage storage ) {
		return storage.projectedOnEveryInstance() ? ProcessorMode.RUNNING_ON_ALL_INSTANCES : ProcessorMode.RUNNING_ON_SINGLE_LEADER;
	}

	/**
	 * Builds a run listener that emits an {@link BoundedContextEvent.EventuallyConsistentReadModelUpdated}
	 * after each projector catch-up that handled at least one event. The metrics come from the projector
	 * run, so {@code queriesDone}/{@code eventsStreamed} are accurate — including the full rebuild of an
	 * ephemeral read model on processor start (which spans several query batches).
	 */
	private ProjectorProcessor.RunListener ecRunListener ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> rm, Storage storage ) {
		// the projector runs on its own (system) thread, not on behalf of any user operation, so the
		// event is emitted with kernel tracing (actor "system", no channel)
		return (metrics, durationMs) -> {
			if ( eventEmitter.enabled() && metrics.eventsHandled() > 0 ) {
				BoundedContextEvent.Metrics m = new BoundedContextEvent.Metrics(durationMs, metrics.queriesDone(), metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());
				eventEmitter.emit(new BoundedContextEvent.EventuallyConsistentReadModelUpdated(boundedContext, rm.readmodelName(), storage.label(), m, eventEmitter.sliceFor(rm.getClass())));
			}
		};
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
				return (T) projectLiveModel(domainEventStream, readModelClass, info, tracing, constructorParams);
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
				return (T) projectLiveModel(allInStorageEventStream, readModelClass, info, tracing, constructorParams);
			});
		} else {
			throw new IllegalArgumentException("unknown live readmodel: " + readModelClass);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> projectLiveModel ( EventSource eventSource, Class readModelClass, LiveModelInfo<DOMAIN_EVENT_TYPE> info, Tracing tracing, Object[] constructorParams ) {
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

			if ( eventEmitter.enabled() ) {
				long finish = System.currentTimeMillis();
				long duration = finish - start;
				BoundedContextEvent.Metrics metrics = new BoundedContextEvent.Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
				eventEmitter.emit(new BoundedContextEvent.LiveModelProjected(boundedContext, readModel.readmodelName(), metrics, eventEmitter.sliceFor(readModel.getClass())), tracing);
			}

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

	/**
	 * Starts the projector processors and does not return until every ephemeral read model has been
	 * projected completely, i.e. has caught up with the stream as it was at start time.
	 * <p>
	 * A read model declaring {@link org.sliceworkz.eventmodeling.readmodels.ReadModelStorage#EPHEMERAL}
	 * storage starts empty on every process start (its bookmark is dropped when the processor is
	 * created), so without this wait the bounded context would serve empty or partially built read
	 * models right after {@code start()}. Durable read models ({@code LOCAL} and {@code SHARED}) keep
	 * their bookmark across restarts and are not waited for — they continue where they left off in
	 * the background.
	 */
	@Override
	public void start ( ) {
		this.processorThreadManager.start();
		awaitEphemeralReadModelsProjected();
	}

	/**
	 * Waits for the initial catch-up of all ephemeral read model processors. They all run
	 * concurrently on their own threads, so the wait takes as long as the slowest one, not the sum.
	 * <p>
	 * The deadline is shared over all of them and defaults to {@value #DEFAULT_EPHEMERAL_PROJECTION_TIMEOUT_MS}
	 * ms; it can be changed with the {@code sliceworkz.eventmodeling.readmodel.ephemeral.projection.timeout.ms}
	 * system property. When it expires the startup continues (with a warning) rather than blocking
	 * the process forever — the read models involved keep catching up in the background.
	 */
	private void awaitEphemeralReadModelsProjected ( ) {
		List<ProjectorProcessor<DOMAIN_EVENT_TYPE>> ephemeralProcessors = projectorProcessors.stream()
				.filter(p -> p.identification().storage() == Storage.EPHEMERAL)
				.toList();

		if ( ephemeralProcessors.isEmpty() ) {
			return;
		}

		LOGGER.info("waiting for {} ephemeral readmodel(s) to be projected: {}", ephemeralProcessors.size(), names(ephemeralProcessors));
		long start = System.currentTimeMillis();
		long deadline = start + EPHEMERAL_PROJECTION_TIMEOUT_MS;

		List<ProjectorProcessor<DOMAIN_EVENT_TYPE>> outstanding = new ArrayList<>(ephemeralProcessors);
		while ( !outstanding.isEmpty() ) {
			long remainingMs = deadline - System.currentTimeMillis();
			if ( remainingMs <= 0 ) {
				LOGGER.warn("{} ephemeral readmodel(s) not projected completely within {} ms, continuing startup - they keep catching up in the background: {}",
						outstanding.size(), EPHEMERAL_PROJECTION_TIMEOUT_MS, names(outstanding));
				return;
			}

			boolean caughtUp;
			try {
				// wait on one of them at a time, but never longer than the progress interval, so the
				// readmodels still outstanding are reported while a long catch-up is going on
				caughtUp = outstanding.get(0).awaitInitialProjection(Math.min(PROGRESS_LOG_INTERVAL_MS, remainingMs), TimeUnit.MILLISECONDS);
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				LOGGER.warn("interrupted while waiting for ephemeral readmodels to be projected, still outstanding: {}", names(outstanding));
				return;
			}

			outstanding.removeIf(ProjectorProcessor::initialProjectionDone);

			if ( !caughtUp && !outstanding.isEmpty() ) {
				LOGGER.info("after {} ms, still waiting for {} ephemeral readmodel(s) to be projected: {}",
						System.currentTimeMillis() - start, outstanding.size(), names(outstanding));
			}
		}

		LOGGER.info("all {} ephemeral readmodel(s) projected in {} ms", ephemeralProcessors.size(), System.currentTimeMillis() - start);
	}

	private String names ( Collection<ProjectorProcessor<DOMAIN_EVENT_TYPE>> processors ) {
		return processors.stream().map(p -> p.identification().id()).collect(Collectors.joining(", "));
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
