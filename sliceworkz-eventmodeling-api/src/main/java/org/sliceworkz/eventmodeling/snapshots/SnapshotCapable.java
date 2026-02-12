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
package org.sliceworkz.eventmodeling.snapshots;

import org.sliceworkz.eventstore.events.Tags;

/**
 * Marks an aggregate or live model as capable of snapshotting for performance optimization.
 * <p>
 * Aggregates and live models with long event histories can implement this interface to enable
 * periodic snapshots of their state. When loading, the component can be restored from the most
 * recent snapshot and then replay only subsequent events, rather than replaying the
 * entire event history from the beginning.
 * <p>
 * Snapshot versioning ensures that snapshots are only loaded if they match the current
 * implementation version, preventing incompatibility issues when the structure changes.
 *
 * @param <SNAPSHOT_TYPE> the type representing the serialized snapshot state
 */
public interface SnapshotCapable<SNAPSHOT_TYPE> {

	/**
	 * Captures the current state as a snapshot.
	 * <p>
	 * The snapshot should contain all state necessary to restore the component
	 * to its current condition without replaying events.
	 *
	 * @return an immutable representation of the current state
	 */
	SNAPSHOT_TYPE takeSnapshot ( );

	/**
	 * Restores state from a previously captured snapshot.
	 * <p>
	 * This method is called before event replay begins, allowing the component
	 * to skip replaying events that occurred before the snapshot was taken.
	 *
	 * @param snapshot the snapshot containing the previous state
	 */
	void fromSnapshot ( SNAPSHOT_TYPE snapshot );

	/**
	 * Returns the version identifier for the snapshot format.
	 * <p>
	 * The version should be changed whenever the snapshot structure is modified
	 * in a way that makes old snapshots incompatible. Only snapshots with matching
	 * versions will be loaded; mismatched snapshots are ignored and the component
	 * is rebuilt from events.
	 *
	 * @return a version identifier for snapshot compatibility checking
	 */
	String version ( );

	/**
	 * Generates a unique storage key for this aggregate's snapshot.
	 * <p>
	 * The key is generated in the format: "name/tag1-value1/tag2-value2" where tags
	 * are sorted alphabetically by key to ensure deterministic key generation regardless
	 * of tag insertion order. Null keys or values are represented as empty strings.
	 * Implementations may override this to provide custom key generation logic.
	 *
	 * @param name the name of the aggregate class
	 * @param identity the tags uniquely identifying this aggregate instance
	 * @return a unique key for storing and retrieving this aggregate's snapshot
	 */
	default String key ( String name, Tags identity ) {
		StringBuilder keyBuilder = new StringBuilder(name != null ? name : "");

		if ( identity != null && identity.tags() != null ) {
			identity.tags().stream()
				.sorted((t1, t2) -> {
					String k1 = t1.key() != null ? t1.key() : "";
					String k2 = t2.key() != null ? t2.key() : "";
					int keyCompare = k1.compareTo(k2);
					if (keyCompare != 0) {
						return keyCompare;
					}
					// If keys are equal (including both null), sort by value for deterministic ordering
					String v1 = t1.value() != null ? t1.value() : "";
					String v2 = t2.value() != null ? t2.value() : "";
					return v1.compareTo(v2);
				})
				.forEach(tag -> {
					keyBuilder.append("/");
					keyBuilder.append(tag.key() != null ? tag.key() : "");
					keyBuilder.append("-");
					keyBuilder.append(tag.value() != null ? tag.value() : "");
				});
		}

		return keyBuilder.toString();
	}

	/**
	 * Generates a unique storage key for a live model's snapshot.
	 * <p>
	 * The key is generated in the format: "name/param1/param2/..." where each constructor
	 * parameter's string representation is appended as a path segment. Null parameters are
	 * represented as empty strings.
	 * Implementations may override this to provide custom key generation logic.
	 *
	 * @param name the name of the read model class
	 * @param constructorParams the constructor parameters uniquely identifying this live model instance
	 * @return a unique key for storing and retrieving this live model's snapshot
	 */
	default String key ( String name, Object... constructorParams ) {
		StringBuilder keyBuilder = new StringBuilder(name != null ? name : "");

		if ( constructorParams != null ) {
			for ( Object param : constructorParams ) {
				keyBuilder.append("/");
				keyBuilder.append(param != null ? param.toString() : "");
			}
		}

		return keyBuilder.toString();
	}

}
