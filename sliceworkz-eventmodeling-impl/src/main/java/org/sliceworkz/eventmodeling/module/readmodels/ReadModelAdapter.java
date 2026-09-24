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

import java.util.Optional;

import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Wraps an eventually consistent ReadModel so that its projection is observed: every batch a
 * {@link Observation.ReadModelBatch} scope, from {@link #beforeBatch()} to the read model's own
 * {@link #afterBatch} (completed {@link Outcome.Projected}) or {@link #cancelBatch()} (completed
 * {@link Outcome.Cancelled}), and every event handed to the read model a {@link Observation.ReadModelUpdate}
 * nested inside it. All of it runs on the projector's thread, which is what the scope contract asks.
 */
class ReadModelAdapter<DOMAIN_EVENT_TYPE> implements BatchAwareProjection<DOMAIN_EVENT_TYPE> {

	private final ReadModel<DOMAIN_EVENT_TYPE> readModel;
	private final String boundedContext;
	private final String readModelName;
	private final ReadModelStorage storage;
	private final BoundedContextObserver observer;

	/** The open batch, null between batches. Only ever touched from the projector's thread. */
	private Observation.Scope<Outcome.BatchResult> batchScope;
	private int batchEventCount;

	public ReadModelAdapter(ReadModel<DOMAIN_EVENT_TYPE> readModel, String boundedContext, BoundedContextObserver observer) {
		this.readModel = readModel;
		this.boundedContext = boundedContext;
		this.readModelName = readModel.readmodelName();
		this.storage = readModel.storage();
		this.observer = observer;
	}

	@Override
	public void when(Event<DOMAIN_EVENT_TYPE> eventWithMeta) {
		try ( Observation.Scope<Outcome.Done> scope = observer.start(new Observation.ReadModelUpdate(boundedContext, readModelName, storage, eventWithMeta)) ) {
			try {
				readModel.when(eventWithMeta);
				scope.completed(Outcome.Done.INSTANCE);
			} catch ( RuntimeException | Error e ) {
				scope.failed(e);
				throw e;
			}
		}
		batchEventCount++;
	}

	@Override
	public void beforeBatch() {
		closeOpenBatch(); // a batch the projector never ended must not stay current on this thread
		batchScope = observer.start(new Observation.ReadModelBatch(boundedContext, readModelName, storage));
		batchEventCount = 0;
		if (readModel instanceof BatchAwareProjection<?> batchAware) {
			batchAware.beforeBatch();
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> lastProcessedEvent) {
		try {
			if (readModel instanceof BatchAwareProjection<?> batchAware) {
				batchAware.afterBatch(lastProcessedEvent);
			}
		} catch ( RuntimeException | Error e ) {
			endBatch(scope -> scope.failed(e));
			throw e;
		}
		int eventsHandled = batchEventCount;
		endBatch(scope -> scope.completed(new Outcome.Projected(eventsHandled, lastProcessedEvent)));
	}

	@Override
	public void cancelBatch() {
		try {
			if (readModel instanceof BatchAwareProjection<?> batchAware) {
				batchAware.cancelBatch();
			}
		} finally {
			endBatch(scope -> scope.completed(new Outcome.Cancelled()));
		}
	}

	private void endBatch ( java.util.function.Consumer<Observation.Scope<Outcome.BatchResult>> outcome ) {
		Observation.Scope<Outcome.BatchResult> scope = batchScope;
		batchScope = null;
		batchEventCount = 0;
		if ( scope != null ) {
			try {
				outcome.accept(scope);
			} finally {
				scope.close();
			}
		}
	}

	private void closeOpenBatch ( ) {
		endBatch(scope -> scope.completed(new Outcome.Cancelled()));
	}

	@Override
	public EventQuery eventQuery() {
		return readModel.eventQuery();
	}
}
