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
 * A read model that records how far it has been projected in <b>its own</b> storage, together with
 * the state that projection produced, and is therefore the authority on where to resume.
 *
 * <p><b>The problem this exists to solve.</b> A durable read model commits its rows in one store and
 * the framework bookmarks its progress in another — the event store — and there is no transaction
 * across the two. The bookmark is written after the commit, which is the right way round (the cost
 * of a crash in that window is a repeat, not a loss), but it does mean a crash between them replays
 * events the read model has already applied. Against an in-memory read model that costs nothing;
 * against a durable one it duplicates inserts and double-counts aggregates, for good.
 *
 * <p><b>The answer is to stop spanning two stores.</b> A read model that writes its position into
 * its own storage inside the same transaction that writes the rows has one atomic step instead of
 * two, and resuming from that position is exactly-once by construction. The event store bookmark
 * stays — it is written per batch and is a perfectly good record for monitoring — but it is no
 * longer consulted to decide where this read model starts.
 *
 * <p><b>Two consequences worth knowing.</b> An empty position means <em>project from the
 * beginning</em>, and deliberately does not fall back to the event store bookmark: that is what
 * makes "drop the tables to rebuild" work, and what keeps a fresh database from being handed a
 * bookmark describing rows it does not have. And because the position lives with the state, the two
 * cannot disagree — an ephemeral database that really did die comes back empty, position included,
 * so it replays; one that survived resumes instead of duplicating.
 *
 * <p>{@link org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector} implements this over
 * a table of its own. Implement it directly for a read model backed by something else that can
 * write its position and its state in one atomic step.
 *
 * @see org.sliceworkz.eventstore.projection.BatchAwareProjection#afterBatch(Optional)
 */
public interface SelfBookmarkingProjection {

	/**
	 * The reference of the last event this read model has durably applied, or empty to be projected
	 * from the beginning of the stream.
	 * <p>
	 * Read once, when the bounded context builds this read model's processor. It must reflect
	 * committed state only: a position ahead of the rows it describes skips events silently, which is
	 * the one failure this whole mechanism exists to prevent. Reporting empty is always safe — the
	 * worst it costs is a replay.
	 * <p>
	 * A failure to read the position is <b>not</b> to be swallowed into an empty result. Empty means
	 * "nothing has been projected", and answering that for a database that is merely unreachable
	 * replays the whole stream into a read model that already holds it. Let the exception out.
	 */
	Optional<EventReference> resumeFrom ( );

}
