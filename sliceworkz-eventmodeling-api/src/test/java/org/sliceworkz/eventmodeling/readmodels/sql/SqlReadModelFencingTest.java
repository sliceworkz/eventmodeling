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
package org.sliceworkz.eventmodeling.readmodels.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.readmodels.StaleLeadershipException;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The fencing token, enforced where the docs promised it would be: inside the batch transaction of
 * a {@link SqlReadModelProjector}. Leader election cannot prevent a leader paused beyond its lease
 * ttl from waking up and committing a batch it had already started — what the fence adds is that
 * such a commit <b>fails hard</b> instead of silently writing over the new leader's rows.
 *
 * <p>Two projector instances against one database play the two leaders here, exactly as a failover
 * leaves them: the superseded one still holding its old token, the new one promoted with a higher
 * one. Run against H2 rather than a mock connection, as the sibling bookmark test is, because the
 * guarded statements have to parse and the guard has to select the right rows.
 */
class SqlReadModelFencingTest {

	private DataSource dataSource;

	@BeforeEach
	void freshDatabase ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		dataSource = h2;
	}

	// -- The ordinary paths stay ordinary --

	@Test
	void aFencedLeaderWritesUnderItsToken ( ) {
		FencedProjector leader = newProjector();
		leader.fencedBy(1);
		leader.resumeFrom();

		EventReference last = project(leader, 1, "a", "b");

		assertEquals(2, entryCount());
		assertEquals(last, leader.resumeFrom().orElseThrow());
		assertEquals(1, storedToken(), "the batch stored the token it was written under");
	}

	/** Renewals keep the token, so batch after batch under the same leadership must keep committing. */
	@Test
	void theSameTokenKeepsWriting ( ) {
		FencedProjector leader = newProjector();
		leader.fencedBy(1);
		leader.resumeFrom();

		project(leader, 1, "a");
		project(leader, 2, "b");

		assertEquals(2, entryCount());
	}

	@Test
	void anUnfencedReadModelWritesExactlyAsBefore ( ) {
		FencedProjector unfenced = newProjector();

		EventReference last = project(unfenced, 1, "a", "b");

		assertEquals(2, entryCount());
		assertEquals(last, unfenced.resumeFrom().orElseThrow());
		assertEquals(0, storedToken(), "no election result was handed over, so nothing is fenced");
	}

	// -- The zombie write, rejected --

	/**
	 * The scenario the token exists for: the old leader pauses past its lease, a new leader is
	 * promoted and projects on, and then the old one wakes up and tries to commit. Its batch must
	 * fail — and fail <em>whole</em>: the rows roll back with the refused bookmark, so nothing of
	 * the superseded leadership lands.
	 */
	@Test
	void aSupersededLeaderCannotCommitItsBatch ( ) {
		FencedProjector oldLeader = newProjector();
		oldLeader.fencedBy(1);
		oldLeader.resumeFrom();
		project(oldLeader, 1, "a");

		FencedProjector newLeader = new FencedProjector(dataSource);
		newLeader.fencedBy(2);
		newLeader.resumeFrom();
		EventReference newLeadersLast = project(newLeader, 2, "b");

		oldLeader.beforeBatch();
		oldLeader.when(event("c", 3, 3));
		StaleLeadershipException rejected = assertThrows(StaleLeadershipException.class,
				() -> oldLeader.afterBatch(Optional.of(reference(3, 3))),
				"a batch written under a superseded token must not commit");

		assertEquals(1, rejected.heldToken());
		assertEquals(2, rejected.storedToken());
		assertEquals(2, entryCount(), "the rejected batch's rows rolled back with its bookmark");
		assertEquals(newLeadersLast, newLeader.resumeFrom().orElseThrow(), "the position is still the new leader's");
		assertEquals(2, storedToken());
	}

	/**
	 * The fence holds from the moment the new leader knows where to resume, not from its first
	 * committed batch: {@code resumeFrom()} raises the stored token <em>before</em> reading the
	 * position. Without that, the whole first catch-up — which can be long — would be a window in
	 * which the zombie's commit still succeeds and then moves the very bookmark the new leader just
	 * resumed from.
	 */
	@Test
	void promotionFencesTheOldLeaderOutBeforeTheNewOneCommitsAnything ( ) {
		FencedProjector oldLeader = newProjector();
		oldLeader.fencedBy(1);
		oldLeader.resumeFrom();
		EventReference oldLeadersLast = project(oldLeader, 1, "a");

		FencedProjector newLeader = new FencedProjector(dataSource);
		newLeader.fencedBy(2);
		newLeader.resumeFrom(); // promoted and resumed, but nothing projected yet

		oldLeader.beforeBatch();
		oldLeader.when(event("b", 2, 2));
		assertThrows(StaleLeadershipException.class, () -> oldLeader.afterBatch(Optional.of(reference(2, 2))));

		assertEquals(1, entryCount(), "the zombie batch did not land although the new leader has committed nothing");
		assertEquals(oldLeadersLast, newLeader.resumeFrom().orElseThrow(),
				"so the position the new leader resumed from is still the position");
	}

	/** And the projector that was fenced out is not poisoned: re-promoted with the newer token, it writes again. */
	@Test
	void aFencedOutProjectorRePromotedWithTheNewerTokenResumes ( ) {
		FencedProjector oldLeader = newProjector();
		oldLeader.fencedBy(1);
		oldLeader.resumeFrom();
		project(oldLeader, 1, "a");

		FencedProjector newLeader = new FencedProjector(dataSource);
		newLeader.fencedBy(2);
		newLeader.resumeFrom();
		project(newLeader, 2, "b");

		oldLeader.beforeBatch();
		oldLeader.when(event("c", 3, 3));
		assertThrows(StaleLeadershipException.class, () -> oldLeader.afterBatch(Optional.of(reference(3, 3))));

		// leadership comes back to the first instance, under a fresh token as a re-acquisition mints one
		oldLeader.fencedBy(3);
		oldLeader.resumeFrom();
		project(oldLeader, 3, "c");

		assertEquals(3, entryCount());
		assertEquals(3, storedToken());
	}

	// -- A database from before the token existed --

	@Test
	void aBookmarkTableFromBeforeFencingIsUpgradedOnFirstUse ( ) {
		execute("""
				CREATE TABLE rm_fenced_projection_bookmark (
					reader VARCHAR(255) NOT NULL PRIMARY KEY,
					%s
				)""".formatted(SqlReadModel.EVENT_REF_COLUMNS));
		EventReference legacy = reference(1, 1);
		execute(("INSERT INTO rm_fenced_projection_bookmark (reader, last_event_id, last_event_position, last_event_tx, last_event_index) "
				+ "VALUES ('FencedProjector', '%s', 1, 1, 0)").formatted(legacy.id().value()));

		FencedProjector projector = new FencedProjector(dataSource);
		projector.ensureTables();

		assertEquals(legacy, projector.resumeFrom().orElseThrow(), "the legacy position survives the upgrade");
		assertEquals(0, storedToken(), "a row from before fencing was never fenced");

		projector.fencedBy(1);
		projector.resumeFrom();
		project(projector, 2, "a");

		assertEquals(1, storedToken(), "and fencing starts working the moment a token arrives");
	}

	// -- helpers --

	private FencedProjector newProjector ( ) {
		FencedProjector projector = new FencedProjector(dataSource);
		projector.ensureTables();
		return projector;
	}

	/** Projects one batch of the given keys, at positions counting up from {@code startAt}. */
	private EventReference project ( FencedProjector projector, long startAt, String... keys ) {
		projector.beforeBatch();
		EventReference last = null;
		for ( int i = 0; i < keys.length; i++ ) {
			Event<TestEvent> e = event(keys[i], startAt + i, startAt + i);
			projector.when(e);
			last = e.reference();
		}
		projector.afterBatch(Optional.ofNullable(last));
		return last;
	}

	private static Event<TestEvent> event ( String key, long position, long tx ) {
		return Event.of(
				EventStreamId.forContext("test"),
				reference(position, tx),
				EventType.of(new TestEvent(key)),
				EventType.of(new TestEvent(key)),
				new TestEvent(key),
				Tags.none(),
				LocalDateTime.now(ZoneOffset.UTC));
	}

	private static EventReference reference ( long position, long tx ) {
		return EventReference.of(EventId.of(UUID.nameUUIDFromBytes(("event-" + position + "-" + tx).getBytes()).toString()), position, tx);
	}

	private int entryCount ( ) {
		return queryInt("SELECT COUNT(*) FROM rm_fenced_entries");
	}

	private long storedToken ( ) {
		return queryInt("SELECT fencing_token FROM rm_fenced_projection_bookmark WHERE reader = 'FencedProjector'");
	}

	private int queryInt ( String sql ) {
		try ( var conn = dataSource.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery(sql) ) {
			assertTrue(rs.next(), "expected a row from: " + sql);
			return rs.getInt(1);
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	private void execute ( String sql ) {
		try ( var conn = dataSource.getConnection();
			  var stmt = conn.createStatement() ) {
			stmt.execute(sql);
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	record TestEvent ( String key ) { }

	/** An append-style read model — the shape whose zombie writes are duplicated rows. */
	static class FencedProjector extends SqlReadModelProjector<TestEvent> {

		FencedProjector ( DataSource dataSource ) {
			super(dataSource, "rm_fenced");
		}

		@Override
		public String readmodelName ( ) {
			return "FencedProjector";
		}

		@Override
		protected String[] createTables ( ) {
			return new String[] { """
					CREATE TABLE IF NOT EXISTS %s (
						key_column VARCHAR(255) NOT NULL,
						%s
					)""".formatted(table("entries"), EVENT_REF_COLUMNS) };
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		protected void project ( Event<TestEvent> event ) {
			// deliberately a bare INSERT rather than insertOnce: whether the fence holds must not
			// depend on the projection happening to be idempotent -- that is the whole point of it
			execute("INSERT INTO %s (key_column, last_event_id, last_event_position, last_event_tx, last_event_index) VALUES (?, ?, ?, ?, ?)"
					.formatted(table("entries")), event.data().key(), eventId(), eventPosition(), eventTx(), eventIndex());
		}

	}

}
