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

/**
 * Registration of a read model given by its class, which the bounded context instantiates per read.
 *
 * <p><b>One of the terminals below has to be called.</b> {@code readmodel(...)} on its own registers
 * the read model but says nothing about how it is projected, and {@code build()} rejects that rather
 * than picking silently — see {@link #live()}.
 *
 * @param <C> the bounded context type
 */
public interface LiveModelSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * Projects this read model <b>when it is read</b>, into a fresh instance built with the read's
	 * parameters.
	 *
	 * <p><b>"Live" is about when it is projected, not about how much it replays.</b> How much depends
	 * on where the projection starts: from the beginning of the stream by default, from a snapshot with
	 * {@link #snapshots}, or from a base the read model loads itself if it implements
	 * {@link SeededReadModel}. All three are live — the answer includes everything the store will show,
	 * with no projector to lag behind.
	 *
	 * <p>The default answer for a read model, and the right one whenever the set of events its
	 * {@code eventQuery()} matches is bounded by design. Where it is not — and where narrowing the
	 * query or introducing a savepoint cannot make it so — the read belongs on a background projection
	 * instead: register an instance with
	 * {@link EventuallyConsistentReadModelSpecification#eventuallyConsistent()}.
	 *
	 * <p><b>Saying it is required</b>, and {@code build()} names the read model that did not. Calling
	 * this is redundant with the overload — a class can only be live — so it exists for the reader and
	 * for the writer: a mode that follows silently from which method was called is one nobody had to
	 * decide, and the difference between the two is one every caller of the read model lives with.
	 * {@link #snapshots} says it too, being a statement about how a live projection starts.
	 */
	BoundedContextBuilder<C> live();

	/**
	 * Not available for a read model registered by class.
	 * <p>
	 * An eventually consistent read model is one long-lived instance the framework projects in the
	 * background, so it is registered as that instance —
	 * {@code builder.readmodel(myReadModel).eventuallyConsistent()} — rather than as a class to build
	 * per read.
	 *
	 * @throws IllegalArgumentException always
	 */
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
