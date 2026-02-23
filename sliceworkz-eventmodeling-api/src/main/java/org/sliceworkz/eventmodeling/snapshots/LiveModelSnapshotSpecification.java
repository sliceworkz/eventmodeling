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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

/**
 * Provides a fluent API for configuring snapshot behavior for a live model.
 * <p>
 * This specification controls when snapshots are taken and whether they should
 * be read during live model projection, written after projection, or both.
 * Snapshot configuration allows fine-tuning of the performance/storage tradeoff.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <INBOUND_EVENT_TYPE> the base type of inbound events from external systems
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events to external systems
 */
public interface LiveModelSnapshotSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Sets the event count threshold for triggering snapshot creation.
	 * <p>
	 * A snapshot is automatically created when the number of events replayed since the last
	 * snapshot (or from the beginning if no snapshot exists) reaches this threshold.
	 * Lower values create snapshots more frequently, trading storage space for faster
	 * live model projection. Higher values reduce storage but may slow projection of live models
	 * with long event histories.
	 *
	 * @param eventCountThreshold number of events before creating a new snapshot, must be greater than 0
	 * @return this specification for method chaining
	 * @throws IllegalArgumentException if threshold is less than or equal to 0
	 */
	LiveModelSnapshotSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventCountThreshold ( int eventCountThreshold );

	/**
	 * Enables both reading and writing of snapshots (default behavior).
	 * <p>
	 * The live model will attempt to load from a snapshot when projecting state,
	 * and will create new snapshots when the event count threshold is reached.
	 *
	 * @return the bounded context builder for further configuration
	 */
	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readAndWrite ( );

	/**
	 * Enables reading snapshots but disables writing new snapshots.
	 * <p>
	 * Useful when transitioning away from snapshotting or when you want to use
	 * existing snapshots but not create new ones. The live model will load from
	 * snapshots but will not create new ones regardless of event count.
	 *
	 * @return the bounded context builder for further configuration
	 */
	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readOnly ( );

	/**
	 * Enables writing new snapshots but disables reading existing snapshots.
	 * <p>
	 * Useful when transitioning to snapshotting or testing snapshot creation.
	 * The live model will always be projected from the full event history,
	 * but new snapshots will be created for future use.
	 *
	 * @return the bounded context builder for further configuration
	 */
	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> writeOnly ( );

}
