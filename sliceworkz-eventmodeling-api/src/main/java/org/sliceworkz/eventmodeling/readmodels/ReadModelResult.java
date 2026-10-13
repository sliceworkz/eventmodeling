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

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Something a read model answered, together with the point in the event stream that answer reflects.
 *
 * <p><b>Why the two travel together.</b> An eventually consistent read model is behind by however far
 * its projector has got, and an answer that does not say how far is one nobody can reason about: a
 * caller cannot tell whether the command it just executed is included, and a
 * {@link SeededReadModel} cannot catch it up — asking for the state and the position separately
 * straddles whatever the projector committed in between, which either re-applies events the state
 * already holds or skips events that are in neither. Handing back one value makes that mistake
 * unavailable.
 *
 * <p><b>What {@code upTo} means, and what it does not.</b> It is what this answer reflects — no
 * event after it is in here. How it was arrived at is the producer's business, and the two producers
 * in this framework differ in a way worth knowing:
 * <ul>
 *   <li>{@link PublishingReadModel#published()} reports the last event it folded, so it is exactly
 *       the position of the state handed back.</li>
 *   <li>{@link org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelQuery#queryListWithRef} reports
 *       the newest event any <em>returned row</em> reflects, which is a <em>lower bound</em> on how
 *       far the read model has been projected — the projector may have committed later events that
 *       touched other rows, or created rows this query did not select.</li>
 * </ul>
 * Which is why a {@link SeededReadModel} seeds from a position that covers the whole read model
 * ({@code SqlReadModelQuery.loadBaseAt}, {@code published()}) rather than from a row's own freshness:
 * a per-row reference cannot account for a row that does not exist yet.
 *
 * <p><b>Checking your own write is in here</b> is what this is for at the call site:
 * {@link #includes(EventReference) result.includes(writeRef)}. Not
 * {@code writeRef.happenedBefore(result.upTo())}, which is strict: it answers {@code false} when the
 * write is the newest event the answer reflects — exactly the case of a write that has just been
 * included. And not a comparison on {@code position()}, which is a different order — see
 * {@code EventReference} for why the two genuinely disagree.
 *
 * @param <R> the type of the answer
 * @param data the answer
 * @param upTo the reference this answer reflects, or {@code null} when nothing has been projected
 *             into it yet
 */
public record ReadModelResult<R> ( R data, EventReference upTo ) {

	/**
	 * Whether this answer reflects the given event: whether the stored event it names is at or before
	 * {@link #upTo()}. Compared over stored events ({@link EventReference#storedEventHappenedAfter}), so
	 * a write is included once the stored event it was appended as has been folded, whichever of the
	 * events that stored event upcasts into {@code upTo} names.
	 * <p>
	 * For an answer whose {@code upTo} is a lower bound — {@code SqlReadModelQuery.queryListWithRef} —
	 * {@code false} means "not proven", not "not in there".
	 *
	 * @param write the reference of the event to look for, typically one a command just appended
	 * @return {@code true} if this answer reflects it, {@code false} if it does not or nothing has been
	 *         projected into it yet
	 * @throws IllegalArgumentException if {@code write} is null
	 */
	public boolean includes ( EventReference write ) {
		if ( write == null ) {
			throw new IllegalArgumentException("write is required");
		}
		return upTo != null && !write.storedEventHappenedAfter(upTo);
	}

}
