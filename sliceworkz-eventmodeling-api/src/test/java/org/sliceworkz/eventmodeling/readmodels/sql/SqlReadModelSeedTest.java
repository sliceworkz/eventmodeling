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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelBookmarkTest.CountingProjector;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelBookmarkTest.TestEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A seeded read model starts from what an eventually consistent projector has already written and is
 * caught up on the spot. That only works if the base it loads and the position that base reflects are
 * one observation — and they are two SELECTs, with a projector committing between them whenever it
 * feels like it.
 *
 * <p>What the two failure modes look like is worth naming, because neither reports anything: read the
 * position <em>first</em> and the caller re-applies events its rows already contain (totals counted
 * twice, rows appended twice); read it <em>afterwards</em> and the events committed in between are in
 * neither the base nor the delta, so they are silently lost from the answer.
 *
 * <p>{@link SqlReadModelQuery#loadBaseAt} closes that window by reading the bookmark on either side of
 * the load and retrying while the two differ, which works because
 * {@link SqlReadModelProjector} commits its rows and its bookmark in one transaction. These tests hold
 * it to that, including the case that motivates it: a batch landing in the middle of a base read.
 *
 * <p>Run against H2 rather than a mock: the point is what the database really returns across
 * concurrent commits, which a mock cannot answer.
 */
class SqlReadModelSeedTest {

	private DataSource dataSource;

	@BeforeEach
	void freshDatabase ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		dataSource = h2;
	}

	@Test
	void theBaseAndThePositionItReflectsComeBackTogether ( ) {
		CountingProjector projector = newProjector();
		EventReference last = project(projector, 1, "a", "a", "b");

		CountingQuery query = new CountingQuery(dataSource);
		Map<String,Integer> base = new LinkedHashMap<>();
		Optional<EventReference> at = query.loadBase(base, null);

		assertEquals(last, at.orElseThrow(), "the base is at the position the projector committed");
		assertEquals(2, base.get("a"));
		assertEquals(1, base.get("b"));
	}

	/**
	 * The empty case is not "no answer", it is "there is nothing to start from" — which tells the
	 * caller to project the whole stream, correctly, because those tables really are empty. The
	 * mistake this guards against is the opposite one: reporting empty for a base that exists, which
	 * replays a history the read model already holds.
	 */
	@Test
	void aReadModelThatHasProjectedNothingReportsNoPosition ( ) {
		newProjector(); // creates the tables, projects nothing

		CountingQuery query = new CountingQuery(dataSource);
		Map<String,Integer> base = new LinkedHashMap<>();

		assertTrue(query.loadBase(base, null).isEmpty(), "nothing projected, so the read starts from the beginning");
		assertTrue(base.isEmpty());
	}

	/**
	 * The case the whole mechanism exists for. The loader reads the totals, a batch commits underneath
	 * it, and the position moves — so what it loaded describes a moment that no longer matches the
	 * bookmark. It has to be read again, and the answer must be the later one.
	 *
	 * <p>Without the retry this returns the pre-batch totals paired with either position, and both
	 * pairings are wrong in a way nothing downstream can detect.
	 */
	@Test
	void aBatchCommittingWhileTheBaseIsBeingReadIsLoadedAgain ( ) {
		CountingProjector projector = newProjector();
		project(projector, 1, "a");

		CountingQuery query = new CountingQuery(dataSource);
		Map<String,Integer> base = new LinkedHashMap<>();

		// commit a second batch during the first load only, so the second load finds a stable position
		EventReference[] committed = new EventReference[1];
		Optional<EventReference> at = query.loadBase(base, loadCount -> {
			if ( loadCount == 1 ) {
				committed[0] = project(projector, 10, "a", "b");
			}
		});

		assertEquals(2, query.loads(), "the base was read again once the position turned out to have moved");
		assertEquals(committed[0], at.orElseThrow(), "and the position returned is the one the base now reflects");
		assertEquals(2, base.get("a"), "the totals are the ones after that batch, not the ones before it");
		assertEquals(1, base.get("b"));
	}

	/**
	 * A missing bookmark table means the read model's tables were never created — not that it has
	 * projected nothing. Answering "nothing projected" there would replay the entire stream on every
	 * read, for as long as nobody notices.
	 */
	@Test
	void aMissingBookmarkTableIsNotReportedAsNothingProjected ( ) {
		CountingQuery query = new CountingQuery(dataSource);

		assertThrows(RuntimeException.class, () -> query.loadBase(new LinkedHashMap<>(), null));
	}

	// -- helpers --

	private CountingProjector newProjector ( ) {
		CountingProjector projector = new CountingProjector(dataSource);
		projector.ensureTables();
		return projector;
	}

	/**
	 * Projects one batch, at positions counting up from {@code fromPosition} — so a later batch really
	 * is later, which is what the freshness guards in the projector compare on.
	 */
	private EventReference project ( CountingProjector projector, int fromPosition, String... keys ) {
		projector.beforeBatch();
		EventReference last = null;
		for ( int i = 0; i < keys.length; i++ ) {
			Event<TestEvent> e = event(keys[i], fromPosition + i, fromPosition + i);
			projector.when(e);
			last = e.reference();
		}
		projector.afterBatch(Optional.ofNullable(last));
		return last;
	}

	private static Event<TestEvent> event ( String key, long position, long tx ) {
		return Event.of(
				EventStreamId.forContext("test"),
				EventReference.of(EventId.create(), position, tx),
				EventType.of(new TestEvent(key)),
				EventType.of(new TestEvent(key)),
				new TestEvent(key),
				Tags.none(),
				LocalDateTime.now(ZoneOffset.UTC));
	}

	/** What a caller sees when the load runs: how many times it has been called so far. */
	@FunctionalInterface
	interface DuringLoad {
		void run ( int loadCount );
	}

	/**
	 * The query side of the counting read model: it selects the totals its projector wrote, together
	 * with the position those totals are at.
	 */
	static class CountingQuery extends SqlReadModelQuery {

		private int loads;

		CountingQuery ( DataSource dataSource ) {
			super(dataSource, "rm_counting");
		}

		int loads ( ) {
			return loads;
		}

		/**
		 * Loads every total into {@code into} and returns the position they reflect.
		 * <p>
		 * Note the {@code clear()}: the loader may be called more than once, and each call has to
		 * replace what the previous one loaded rather than add to it.
		 */
		Optional<EventReference> loadBase ( Map<String,Integer> into, DuringLoad duringLoad ) {
			return loadBaseAt("CountingProjector", conn -> {
				loads++;
				into.clear();
				try ( var stmt = conn.prepareStatement("SELECT key_column, total FROM " + table("totals"));
					  var rs = stmt.executeQuery() ) {
					while ( rs.next() ) {
						into.put(rs.getString(1), rs.getInt(2));
					}
				}
				if ( duringLoad != null ) {
					duringLoad.run(loads);
				}
			});
		}

	}

}
