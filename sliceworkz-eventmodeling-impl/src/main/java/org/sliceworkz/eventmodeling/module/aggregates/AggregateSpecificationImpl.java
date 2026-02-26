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
package org.sliceworkz.eventmodeling.module.aggregates;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.module.snapshots.SnapshotSpecificationImpl;
import org.sliceworkz.eventmodeling.snapshots.SnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;

@SuppressWarnings({"unchecked", "rawtypes"})
public class AggregateSpecificationImpl<C extends BoundedContext<?,?,?>> implements AggregateSpecification<C> {

	private BoundedContextBuilder<C> parent;

	private Class<? extends Aggregate<?>> aggregateClass;
	private SnapshotSpecificationImpl snapshotSpecification = new SnapshotSpecificationImpl(parent, null);

	public AggregateSpecificationImpl ( BoundedContextBuilder<C> parent, Class<? extends Aggregate<?>> aggregateClass ) {
		this.parent = parent;
		this.aggregateClass = aggregateClass;
	}

	@Override
	public BoundedContextBuilder<C> done() {
		return parent;
	}

	public Class<? extends Aggregate<?>> aggregateClass ( ) {
		return aggregateClass;
	}

	public SnapshotStorage<Object> snapshotStorage ( ) {
		return snapshotSpecification == null?null:(SnapshotStorage<Object>)snapshotSpecification.snapshotStorage();
	}

	public boolean readSnapshots ( ) {
		return snapshotSpecification == null?false:snapshotSpecification.readAndOrWrite().mustRead();
	}

	public boolean writeSnapshots ( ) {
		return snapshotSpecification == null?false:snapshotSpecification.readAndOrWrite().mustWrite();
	}

	public int snapshotEventCountThreshold ( ) {
		return snapshotSpecification == null?0:snapshotSpecification.eventCountThreshold();
	}

	@Override
	public <SNAPSHOT_TYPE> SnapshotSpecification<C> snapshots(
			SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage) {
		if ( snapshotStorage == null ) {
			throw new IllegalArgumentException("snapshotStorage can not be null");
		}
		this.snapshotSpecification = new SnapshotSpecificationImpl(parent, snapshotStorage);
		return snapshotSpecification;
	}

}
