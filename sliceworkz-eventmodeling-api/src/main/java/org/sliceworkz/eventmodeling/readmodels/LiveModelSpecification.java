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
package org.sliceworkz.eventmodeling.readmodels;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.snapshots.LiveModelSnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;

public interface LiveModelSpecification<C extends BoundedContext<?,?,?>> {

	BoundedContextBuilder<C> live();

	BoundedContextBuilder<C> eventuallyConsistent();

	/**
	 * Configures snapshotting for this live model to optimize projection performance.
	 * <p>
	 * Snapshots store the complete state of a live model at a point in time, allowing
	 * faster projection by avoiding replay of the entire event history. The live model
	 * must implement {@link org.sliceworkz.eventmodeling.snapshots.SnapshotCapable} to use this feature.
	 *
	 * @param <SNAPSHOT_TYPE> the type representing the live model's snapshot state
	 * @param snapshotStorage the storage mechanism for persisting and loading snapshots
	 * @return a snapshot specification for further configuration
	 */
	<SNAPSHOT_TYPE> LiveModelSnapshotSpecification<C> snapshots ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage );

}
