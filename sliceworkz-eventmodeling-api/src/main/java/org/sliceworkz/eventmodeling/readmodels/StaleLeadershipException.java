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

/**
 * A batch was rejected because the projector writing it has been superseded: the fencing token it
 * holds is older than the one stored next to the read model's bookmark, so a newer leader has taken
 * this projection over and fenced this instance out. The batch is rolled back — nothing of it lands.
 * <p>
 * This is the hard stop for the one overlap leader election deliberately does not promise to
 * prevent: a leader paused beyond its lease ttl (a long GC, a suspended container) resumes believing
 * it is leader and commits work the newly elected leader is already doing. Without the fence that
 * write succeeds silently — against a {@code SHARED} SQL read model, duplicated rows and
 * double-counted totals contained only by how idempotent each {@code project()} happens to be. With
 * it, the zombie's commit fails here, its processor retires itself, and its instance releases the
 * lease it wrongly believed it still held.
 * <p>
 * Not worth retrying on the throwing instance: the stored token only ever grows, so the same batch
 * is rejected again for as long as this instance is not re-elected — and once it is, it is promoted
 * with a newer token and resumes from the position the fence protected.
 *
 * @see SelfBookmarkingProjection#fencedBy(long)
 */
public class StaleLeadershipException extends RuntimeException {

	private final String reader;
	private final long heldToken;
	private final long storedToken;

	public StaleLeadershipException ( String reader, long heldToken, long storedToken ) {
		super(("readmodel '%s' was fenced out: it writes under fencing token %d but token %d is already stored -- "
				+ "a newer leader has taken over this projection, so this batch is rolled back")
				.formatted(reader, heldToken, storedToken));
		this.reader = reader;
		this.heldToken = heldToken;
		this.storedToken = storedToken;
	}

	/** The bookmark reader name of the read model whose batch was rejected. */
	public String reader ( ) {
		return reader;
	}

	/** The fencing token the rejected writer was holding. */
	public long heldToken ( ) {
		return heldToken;
	}

	/** The newer token stored next to the bookmark — the leadership that fenced the writer out. */
	public long storedToken ( ) {
		return storedToken;
	}

}
