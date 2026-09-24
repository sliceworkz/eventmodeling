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
package org.sliceworkz.eventmodeling.module.snapshots;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Observation.SnapshotOwner;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.SnapshotRecord;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * The one place snapshot storage calls are observed, for aggregates and live models alike — the two
 * paths differ only in the {@link SnapshotOwner} and component name this was constructed with. Every load
 * and save goes through here, so what is reported cannot drift apart between the two call sites.
 * <p>
 * A load is an {@link Observation.SnapshotLoad} completing with {@link Outcome.SnapshotFound} or
 * {@link Outcome.SnapshotMissed} — the miss carrying the reason {@link SnapshotStorage#classifyMiss} gives,
 * which is what makes a bumped snapshot version visible: without it, a full replay on every read is
 * indistinguishable from a key that was never snapshotted. A save is an {@link Observation.SnapshotSave}.
 * A load or save that throws fails its scope, and the throw is rethrown unchanged.
 * <p>
 * A throwing {@link SnapshotStorage#classifyMiss} is contained here (the miss is reported as
 * {@link MissReason#UNKNOWN}): classification is observability and must never fail the read it observes.
 */
public class ObservedSnapshots {

	private static final Logger LOGGER = LoggerFactory.getLogger(ObservedSnapshots.class);

	private final BoundedContextObserver observer;
	private final String boundedContext;
	private final SnapshotOwner owner;
	private final String component;

	public ObservedSnapshots ( BoundedContextObserver observer, String boundedContext, SnapshotOwner owner, String component ) {
		this.observer = observer;
		this.boundedContext = boundedContext;
		this.owner = owner;
		this.component = component;
	}

	/**
	 * Loads through the given storage, reporting a hit, a miss with the reason the storage gives, or the
	 * throw before it is rethrown.
	 */
	public <SNAPSHOT_TYPE> Optional<SnapshotRecord<SNAPSHOT_TYPE>> load ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage, String key, String version ) {
		try ( Observation.Scope<Outcome.SnapshotLoadResult> scope = observer.start(new Observation.SnapshotLoad(boundedContext, owner, component, key, version)) ) {
			try {
				Optional<SnapshotRecord<SNAPSHOT_TYPE>> result = snapshotStorage.load(key, version);
				scope.completed(result.isPresent()
						? new Outcome.SnapshotFound(result.get().lastEventReference())
						: new Outcome.SnapshotMissed(classifyMissQuietly(snapshotStorage, key, version)));
				return result;
			} catch ( RuntimeException e ) {
				scope.failed(e);
				throw e;
			}
		}
	}

	/**
	 * Saves through the given storage, reporting the save — or the throw, before it is rethrown.
	 */
	public <SNAPSHOT_TYPE> void save ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage, String key, String version, SNAPSHOT_TYPE snapshot, EventReference lastEventReference ) {
		try ( Observation.Scope<Outcome.Done> scope = observer.start(new Observation.SnapshotSave(boundedContext, owner, component, key, version, lastEventReference)) ) {
			try {
				snapshotStorage.save(key, version, snapshot, lastEventReference);
				scope.completed(Outcome.Done.INSTANCE);
			} catch ( RuntimeException e ) {
				scope.failed(e);
				throw e;
			}
		}
	}

	private MissReason classifyMissQuietly ( SnapshotStorage<?> snapshotStorage, String key, String version ) {
		try {
			MissReason reason = snapshotStorage.classifyMiss(key, version);
			return reason != null ? reason : MissReason.UNKNOWN;
		} catch ( RuntimeException e ) {
			LOGGER.warn("classifyMiss failed for snapshot key '%s', reporting the miss as %s".formatted(key, MissReason.UNKNOWN), e);
			return MissReason.UNKNOWN;
		}
	}

}
