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

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * A live read model that starts from a base it loads itself, and is then projected only over the
 * events after that base — so a read costs one load plus the outstanding delta, never a full replay.
 *
 * <p><b>What this is for.</b> A live model gives the latest state and pays the whole event history
 * for it; an eventually consistent read model is already materialised but lags its projector. This is
 * the combination of the two: take the materialised state as a starting point, and catch it up on the
 * spot with the events that have not reached it yet. It is the answer where a live model is
 * impractical because of the number of events, and an eventually consistent read is not current
 * enough to decide on.
 *
 * <p><b>It is the counterpart of {@link SelfBookmarkingProjection}.</b> That one says where a
 * <em>projector</em> resumes writing; this one says where a <em>read</em> resumes projecting. Both
 * answer with a position, and both are only correct if that position is atomic with the state it
 * describes — see {@link #seed()}.
 *
 * <p><b>Registration is unchanged</b>: {@code builder.readmodel(MyFreshModel.class).live()}. Seeding
 * is a property of the class rather than of the registration, because unlike
 * {@link org.sliceworkz.eventmodeling.snapshots.SnapshotCapable} there is nothing external to
 * configure — the read model knows where its own base lives. The framework instantiates it with the
 * read's parameters, calls {@link #seed()} once, and projects from there.
 *
 * <p><b>What it does not change.</b> The delta is projected by the same {@code Projector} a live
 * model uses, so it is paged, upcasted and ordered on the total {@code (tx, position, index)} order
 * exactly as a full projection is. And it is no fresher than a live model: an event still in flight
 * is withheld from every reader alike (on PostgreSQL by the {@code pg_snapshot_xmin} barrier), so
 * this buys the cost of a live model down, not the visibility rules away.
 *
 * <p><b>Scope the seed to the read.</b> The instance is constructed with the read's parameters before
 * {@link #seed()} is called, so both the base and {@link #eventQuery()} can be narrowed to the one
 * thing being asked for — one row in, a handful of delta events, a small object out. A seed that
 * loads the whole model on every read is a live model with extra steps.
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type this read model is projected from
 * @see PublishingReadModel for the in-memory base a seed reads from
 * @see org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelQuery#loadBaseAt for the SQL one
 */
public interface SeededReadModel<DOMAIN_EVENT_TYPE> extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE> {

	/**
	 * Loads this instance's base state and returns the reference that state reflects. Events after
	 * that reference are then projected onto it, and nothing before it is read.
	 *
	 * <p>Called once per read, after construction and before any event is handled.
	 *
	 * <p><b>Empty means "load nothing, project everything"</b> — a full replay from the start of the
	 * stream. It is <em>not</em> how a thing that has no state yet is reported: an account with no
	 * rows, a key absent from the model, a customer who has done nothing, are all <em>no state
	 * loaded</em> together with {@code Optional.of(position)}. The difference between the two is the
	 * whole event history, and it does not show up as an error — only as a read that is inexplicably
	 * slow, and only once the stream is long enough to notice.
	 *
	 * <p><b>The state and the position must come from one atomic observation.</b> A base read at one
	 * moment and a position read at another straddle whatever the projector committed in between:
	 * reading the position first re-applies events the base already contains, reading it afterwards
	 * skips events that are in neither. Both are silent, and which one you get depends on the order
	 * the two reads happen to be written in. {@code SqlReadModelQuery.loadBaseAt} and
	 * {@link PublishingReadModel#published()} exist so that this does not have to be solved twice.
	 *
	 * <p><b>A failure is not to be degraded into an empty result</b>, for the reason
	 * {@link SelfBookmarkingProjection#resumeFrom()} gives: a base that is merely unreachable would
	 * then be reported as "nothing to start from" and replay the entire stream. Let the exception out.
	 *
	 * @return the reference the loaded base reflects, or empty to be projected from the beginning
	 */
	Optional<EventReference> seed ( );

}
