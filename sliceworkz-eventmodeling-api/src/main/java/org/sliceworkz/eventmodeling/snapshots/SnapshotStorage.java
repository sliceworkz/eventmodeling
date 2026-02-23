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
package org.sliceworkz.eventmodeling.snapshots;

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Defines the contract for storing and retrieving aggregate snapshots.
 * <p>
 * Implementations provide the persistence mechanism for snapshots, which can be
 * in-memory, database, filesystem, or any other storage system. Snapshot versioning
 * ensures that only compatible snapshots are loaded.
 *
 * @param <SNAPSHOT_TYPE> the type representing the serialized snapshot state
 */
public interface SnapshotStorage<SNAPSHOT_TYPE> {

	/**
	 * Loads a snapshot for the given key if one exists with a matching version.
	 * <p>
	 * Version matching ensures snapshot compatibility - a snapshot is only returned
	 * if its stored version exactly matches the requested version. This prevents
	 * loading snapshots that were created with a different aggregate structure.
	 *
	 * @param key unique identifier for the aggregate snapshot
	 * @param version the expected snapshot version for compatibility checking
	 * @return an Optional containing the snapshot record if found with matching version, empty otherwise
	 */
	Optional<SnapshotRecord<SNAPSHOT_TYPE>> load ( String key, String version );

	/**
	 * Persists a snapshot with its version and the reference to the last event included.
	 * <p>
	 * The event reference allows the framework to resume event replay from the correct
	 * position when loading the aggregate. Subsequent events after this reference will
	 * be replayed on top of the restored snapshot state.
	 *
	 * @param key unique identifier for the aggregate snapshot
	 * @param version version identifier for snapshot compatibility checking
	 * @param snapshot the aggregate state to persist
	 * @param eventReference reference to the last event included in this snapshot
	 */
	void save ( String key, String version, SNAPSHOT_TYPE snapshot, EventReference eventReference );

	/**
	 * Container for a snapshot and its associated event reference.
	 * <p>
	 * The event reference indicates the last event that was applied to the aggregate
	 * before the snapshot was taken, allowing proper resume of event replay.
	 *
	 * @param <SNAPSHOT_TYPE> the type representing the serialized snapshot state
	 * @param snapshot the persisted aggregate state
	 * @param lastEventReference reference to the last event included in the snapshot
	 */
	public record SnapshotRecord<SNAPSHOT_TYPE> ( SNAPSHOT_TYPE snapshot, EventReference lastEventReference ) {

	}

}
