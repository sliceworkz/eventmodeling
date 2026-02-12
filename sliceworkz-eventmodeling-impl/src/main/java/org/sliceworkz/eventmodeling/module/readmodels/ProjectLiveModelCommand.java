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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger.Metrics;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule.LiveModelInfo;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventSource;

class ProjectLiveModelCommand<DOMAIN_EVENT_TYPE> implements Command<KernelEvent> {

	private Logger LOGGER = LoggerFactory.getLogger(ProjectLiveModelCommand.class);

	private Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass;
	private Object[] constructorParams;
	private EventSource<DOMAIN_EVENT_TYPE> eventSource;
	private String boundedContext;
	private Instance instance;
	private LiveModelInfo<DOMAIN_EVENT_TYPE> liveModelInfo;

	private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel;

	public ProjectLiveModelCommand ( String boundedContext, Instance instance, EventSource<DOMAIN_EVENT_TYPE> eventSource, Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass, LiveModelInfo<DOMAIN_EVENT_TYPE> liveModelInfo, Object... constructorParams ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.eventSource = eventSource;
		this.readModelClass = readModelClass;
		this.liveModelInfo = liveModelInfo;
		this.constructorParams = constructorParams;
	}

	public ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel ( ) {
		return readModel;
	}

	@SuppressWarnings("unchecked")
	@Override
	public CommandResult<KernelEvent,KernelEvent> execute(CommandContext<KernelEvent, KernelEvent> context) {
		long start = System.currentTimeMillis();
		CommandResult<KernelEvent,KernelEvent> result = context.noDecisionModels();
		try {
			readModel = (ReadModelWithMetaData<DOMAIN_EVENT_TYPE>) selectConstructor(readModelClass, constructorParams).newInstance(constructorParams);

			EventReference lastEventReference = null;

			// Load snapshot if configured and read model implements SnapshotCapable
			if ( liveModelInfo.readSnapshots() && readModel instanceof SnapshotCapable<?> snapshotCapable ) {
				String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
				String version = snapshotCapable.version();
				var loadedSnapshot = liveModelInfo.snapshotStorage().load(key, version);
				if ( loadedSnapshot.isPresent() ) {
					liveModelInfo.counterSnapshotRead().increment();
					((SnapshotCapable<Object>) snapshotCapable).fromSnapshot(loadedSnapshot.get().snapshot());
					lastEventReference = loadedSnapshot.get().lastEventReference();
				}
			}

			// Replay events — starting after snapshot's last event reference if available
			Projector<DOMAIN_EVENT_TYPE> projector = Projector.from(eventSource).towards(readModel).startingAfter(lastEventReference).build();
			ProjectorMetrics projectorMetrics = projector.run();

			// Save snapshot if configured and threshold met
			saveSnapshotIfNeeded(projectorMetrics);

			long finish = System.currentTimeMillis();
			long duration = finish - start;
			Metrics metrics = map(duration, projectorMetrics);
			PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("readmodel.live").readmodel(readModel.readmodelName()).log();
			return result;
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			LOGGER.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private void saveSnapshotIfNeeded ( ProjectorMetrics projectorMetrics ) {
		SnapshotStorage<Object> snapshotStorageForWrite = liveModelInfo.snapshotStorageForWrite();
		if ( snapshotStorageForWrite != null
				&& projectorMetrics.eventsStreamed() >= liveModelInfo.snapshotEventCountThreshold()
				&& readModel instanceof SnapshotCapable<?> snapshotCapable ) {
			String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
			snapshotStorageForWrite.save(key, snapshotCapable.version(), ((SnapshotCapable<Object>) snapshotCapable).takeSnapshot(), projectorMetrics.lastEventReference());
			liveModelInfo.counterSnapshotWrite().increment();
		}
	}

	Constructor<?> selectConstructor ( Class<?> readModelClass, Object[] constructorParams ) {
		for ( Constructor<?> ctr : readModelClass.getDeclaredConstructors() ) {
			// TODO improve constructor selection, not only on parameter count but also on type!
			if ( ctr.getParameterCount() == constructorParams.length ) {
				return ctr;
			}
		}
		throw new IllegalArgumentException("no public constructur found on " + readModelClass + " for parameters " + constructorParams);
	}

	private Metrics map ( long duration, ProjectorMetrics projectorMetrics ) {
		return new Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
	}

}
