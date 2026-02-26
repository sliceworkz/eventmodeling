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
package org.sliceworkz.eventmodeling.aggregates;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.snapshots.SnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;

/**
 * Provides a fluent API for configuring aggregate behavior within a bounded context.
 *
 * @param <C> the bounded context type
 */
public interface AggregateSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * Configures snapshotting for this aggregate to optimize loading performance.
	 *
	 * @param <SNAPSHOT_TYPE> the type representing the aggregate's snapshot state
	 * @param snapshotStorage the storage mechanism for persisting and loading snapshots
	 * @return a snapshot specification for further configuration
	 */
	<SNAPSHOT_TYPE> SnapshotSpecification<C> snapshots ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage );

	/**
	 * Completes the aggregate specification and returns to the bounded context builder.
	 *
	 * @return the bounded context builder for further configuration
	 */
	BoundedContextBuilder<C> done ( );

}
