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
package org.sliceworkz.eventmodeling.module.snapshots;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.module.snapshots.SnapshotSpecificationImpl.READ_AND_OR_WRITE;
import org.sliceworkz.eventmodeling.snapshots.LiveModelSnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;

public class LiveModelSnapshotSpecificationImpl<SNAPSHOT_TYPE, DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> implements LiveModelSnapshotSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	public static final int DEFAULT_EVENT_COUNT_THRESHOLD = 100;

	private SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage;
	private READ_AND_OR_WRITE readAndOrWrite = READ_AND_OR_WRITE.READ_WRITE;
	private int eventCountThreshold = DEFAULT_EVENT_COUNT_THRESHOLD;
	private BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> parent;

	public LiveModelSnapshotSpecificationImpl ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> parent, SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage ) {
		this.parent = parent;
		this.snapshotStorage = snapshotStorage;
		if ( snapshotStorage != null ) {
			this.readAndOrWrite = READ_AND_OR_WRITE.READ_WRITE;
		} else {
			this.readAndOrWrite = READ_AND_OR_WRITE.NO_READ_NO_WRITE;
		}
	}

	@Override
	public LiveModelSnapshotSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventCountThreshold ( int eventCountThreshold ) {
		validateSnapshotStorage();
		if ( eventCountThreshold <= 0 ) {
			throw new IllegalArgumentException("eventCountThreshold must be above 0");
		}
		this.eventCountThreshold = eventCountThreshold;
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readAndWrite ( ) {
		validateSnapshotStorage();
		this.readAndOrWrite = READ_AND_OR_WRITE.READ_WRITE;
		return parent;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readOnly ( ) {
		validateSnapshotStorage();
		this.readAndOrWrite = READ_AND_OR_WRITE.READ_ONLY;
		return parent;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> writeOnly ( ) {
		validateSnapshotStorage();
		this.readAndOrWrite = READ_AND_OR_WRITE.WRITE_ONLY;
		return parent;
	}

	private void validateSnapshotStorage ( ) {
		if ( snapshotStorage == null ) {
			throw new IllegalArgumentException("snapshot storage cannot be null");
		}
	}

	public READ_AND_OR_WRITE readAndOrWrite ( ) {
		return readAndOrWrite;
	}

	public SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage ( ) {
		return snapshotStorage;
	}

	public int eventCountThreshold ( ) {
		return eventCountThreshold;
	}

}
