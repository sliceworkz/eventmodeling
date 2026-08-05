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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A durable SQL read model records how far it has come in its own database, in the transaction that
 * writes the rows — so its state and its position cannot disagree, and a restart neither replays
 * what it already applied nor skips what it did not.
 *
 * <p>The failure being prevented is worth naming, because it is silent: the framework's bookmark
 * lives in the event store and is written after the read model commits, so a crash in that window
 * hands the read model events it has already projected. Against a durable, shared read model that
 * is duplicated rows and double-counted totals, permanently, with nothing raised.
 *
 * <p>Run against H2 rather than a mock connection: the statements here have to parse and the
 * freshness comparison has to select the right rows, neither of which a mock can answer.
 */
class SqlReadModelBookmarkTest {

	private DataSource dataSource;

	@BeforeEach
	void freshDatabase ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		dataSource = h2;
	}

	// -- Where it resumes --

	@Test
	void aReadModelThatHasProjectedNothingReplaysFromTheBeginning ( ) {
		CountingProjector projector = newProjector();

		assertTrue(projector.resumeFrom().isEmpty(),
				"an empty read model reports no position, so the whole stream is replayed into it");
	}

	/**
	 * Deliberately answered without the caller having ensured any table: the bounded context asks a
	 * read model where to resume while it is still building its processor.
	 */
	@Test
	void thePositionCanBeAskedForBeforeTheTablesHaveBeenEnsured ( ) {
		CountingProjector projector = new CountingProjector(dataSource);

		assertTrue(projector.resumeFrom().isEmpty());
	}

	@Test
	void aCommittedBatchLeavesThePositionItReached ( ) {
		CountingProjector projector = newProjector();

		EventReference last = project(projector, "a", "b", "c");

		assertEquals(last, projector.resumeFrom().orElseThrow(),
				"the position is the last event of the batch, so the next run starts after it");
	}

	@Test
	void aRestartedReadModelResumesWhereItsOwnStorageSaysItGotTo ( ) {
		CountingProjector first = newProjector();
		EventReference last = project(first, "a", "b");

		CountingProjector restarted = newProjector();

		assertEquals(last, restarted.resumeFrom().orElseThrow(),
				"the position outlives the projector -- it lives with the rows, not in memory");
	}

	/**
	 * The rows and the position go together or not at all. This is the whole reason the bookmark
	 * write sits inside the batch transaction rather than beside it.
	 */
	@Test
	void aRolledBackBatchLeavesNoPositionAndNoRows ( ) {
		CountingProjector projector = newProjector();

		projector.beforeBatch();
		projector.when(event("a", 1, 1));
		projector.cancelBatch();

		assertTrue(projector.resumeFrom().isEmpty(), "a batch that did not commit did not happen");
		assertEquals(0, rowCount(), "and neither did its rows");
	}

	/**
	 * Dropping the tables is how a read model is rebuilt, and it has to take the position with it.
	 * A position surviving the rows would leave the rebuild permanently missing everything before it.
	 */
	@Test
	void droppingTheReadModelTablesResetsItsPosition ( ) {
		CountingProjector projector = newProjector();
		project(projector, "a", "b");
		assertFalse(projector.resumeFrom().isEmpty());

		execute("DROP TABLE rm_counting_projection_bookmark");
		execute("DROP TABLE rm_counting_totals");

		assertTrue(newProjector().resumeFrom().isEmpty(), "a dropped read model replays from the start");
	}

	/** Two read models sharing a table prefix keep their own positions. */
	@Test
	void positionsAreKeyedByReadModelName ( ) {
		CountingProjector one = newProjector();
		CountingProjector two = new CountingProjector(dataSource) {
			@Override
			public String readmodelName ( ) {
				return "OtherReadModel";
			}
		};
		two.ensureTables();

		project(one, "a", "b");

		assertFalse(one.resumeFrom().isEmpty());
		assertTrue(two.resumeFrom().isEmpty(), "a read model of its own name has projected nothing");
	}

	// -- Idempotent writes --

	/**
	 * The case the whole mechanism is about: an increment applied twice is not the same as applied
	 * once. Re-projecting the same events must leave the total where it was.
	 */
	@Test
	void anIncrementReplayedDoesNotCountTwice ( ) {
		CountingProjector projector = newProjector();

		Event<TestEvent> first = event("acme", 1, 1);
		Event<TestEvent> second = event("acme", 2, 2);

		projector.beforeBatch();
		projector.when(first);
		projector.when(second);
		projector.afterBatch(Optional.of(second.reference()));

		assertEquals(2, totalFor("acme"));

		// exactly what a replay hands it: the same events, again
		projector.beforeBatch();
		projector.when(first);
		projector.when(second);
		projector.afterBatch(Optional.of(second.reference()));

		assertEquals(2, totalFor("acme"), "the replayed increments were recognised as already applied");
	}

	/**
	 * Several events appending to the same table <em>within one batch</em> each get their row. This
	 * is the case that catches an {@code insertOnce} written as a single
	 * {@code INSERT ... WHERE NOT EXISTS}: H2 caches that subquery's result across executions of the
	 * statement inside a transaction, so the second event either loses its row or dies on the unique
	 * index, depending on the table. It passes against PostgreSQL either way, which is exactly how
	 * such a thing reaches production.
	 */
	@Test
	void severalEventsInOneBatchEachAppendTheirOwnRow ( ) {
		CountingProjector projector = newProjector();

		projector.beforeBatch();
		projector.when(event("a", 1, 1));
		projector.when(event("b", 2, 2));
		projector.when(event("c", 3, 3));
		projector.afterBatch(Optional.of(EventReference.of(EventId.create(), 3, 3)));

		assertEquals(3, entryCount(), "one row per event, all in the same transaction");
	}

	@Test
	void anInsertReplayedDoesNotProduceASecondRow ( ) {
		CountingProjector projector = newProjector();

		Event<TestEvent> only = event("acme", 1, 1);

		projector.beforeBatch();
		projector.when(only);
		projector.afterBatch(Optional.of(only.reference()));

		projector.beforeBatch();
		projector.when(only);
		projector.afterBatch(Optional.of(only.reference()));

		assertEquals(1, entryCount(), "the row is keyed by the event that produced it");
	}

	/**
	 * The trap in doing this by hand. A position is a {@code bigserial} and a transaction id an
	 * {@code xid8}, assigned independently, so an event genuinely can carry a <em>lower</em> position
	 * than one that committed before it. A guard comparing {@code last_event_position} alone would
	 * read this event as already applied and drop it — a silently missing update, which is worse than
	 * the duplicate it was trying to prevent.
	 */
	@Test
	void aLaterTransactionWithALowerPositionIsStillApplied ( ) {
		CountingProjector projector = newProjector();

		Event<TestEvent> earlier = event("acme", 50, 1);   // position 50, tx 1
		Event<TestEvent> later = event("acme", 10, 2);     // position 10, tx 2 -- after it in the real order

		projector.beforeBatch();
		projector.when(earlier);
		projector.afterBatch(Optional.of(earlier.reference()));

		projector.beforeBatch();
		projector.when(later);
		projector.afterBatch(Optional.of(later.reference()));

		assertEquals(2, totalFor("acme"),
				"the second event is after the first over (tx, position, index) and must be applied");
	}

	/** And the same event arriving again really is recognised, tuple and all. */
	@Test
	void anEarlierTransactionIsRecognisedAsAlreadyApplied ( ) {
		CountingProjector projector = newProjector();

		Event<TestEvent> later = event("acme", 10, 2);
		Event<TestEvent> earlier = event("acme", 50, 1);

		projector.beforeBatch();
		projector.when(later);
		projector.afterBatch(Optional.of(later.reference()));

		projector.beforeBatch();
		projector.when(earlier);
		projector.afterBatch(Optional.of(earlier.reference()));

		assertEquals(1, totalFor("acme"), "an event before the one the row reflects is not applied again");
	}

	// -- The aggregate: one row, many events --

	/**
	 * There is no one-row-per-event assumption anywhere in this. A customer aggregate —
	 * {@code (customer_id, total_order_count, last_order_date)} fed by a stream of orders — has many
	 * events per row, and the freshness columns record the newest event the <em>row</em> reflects
	 * rather than the event that created it.
	 */
	@Test
	void anAggregateRowFedByManyEventsCountsEachOfThemOnce ( ) {
		OrderCountProjector projector = new OrderCountProjector(dataSource);
		projector.ensureTables();

		Order first = new Order("acme", "2026-01-01");
		Order second = new Order("acme", "2026-01-02");
		Order other = new Order("globex", "2026-01-03");

		projectOrders(projector, first, second, other);

		assertEquals(2, orderCount("acme"));
		assertEquals("2026-01-02", lastOrderDate("acme"));
		assertEquals(1, orderCount("globex"));

		// the same three orders again, as a replay hands them over
		projectOrders(projector, first, second, other);

		assertEquals(2, orderCount("acme"), "a replayed order does not count twice against its customer");
		assertEquals(1, orderCount("globex"));
	}

	/**
	 * And the first event for a customer both creates the row and counts. Recording the event's
	 * reference on the freshly inserted row would make the following update skip it, so the very
	 * first order of every customer would go missing — which is why {@code insertIfAbsent} leaves
	 * those columns alone.
	 */
	@Test
	void theOrderThatCreatesACustomerRowIsAlsoCounted ( ) {
		OrderCountProjector projector = new OrderCountProjector(dataSource);
		projector.ensureTables();

		projectOrders(projector, new Order("acme", "2026-01-01"));

		assertEquals(1, orderCount("acme"), "the first order creates the row and is counted on it");
	}

	// -- Opting out --

	@Test
	void aReadModelThatOptsOutKeepsNoPositionOfItsOwn ( ) {
		CountingProjector projector = new CountingProjector(dataSource) {
			@Override
			protected boolean tracksItsOwnBookmark ( ) {
				return false;
			}
		};
		projector.ensureTables();

		project(projector, "a", "b");

		assertEquals(2, totalFor("a") + totalFor("b"), "the projection still runs");
		assertTrue(projector.resumeFrom().isEmpty(), "but reports no position, so the framework's bookmark decides");
	}

	// -- helpers --

	private CountingProjector newProjector ( ) {
		CountingProjector projector = new CountingProjector(dataSource);
		projector.ensureTables();
		return projector;
	}

	private EventReference project ( CountingProjector projector, String... keys ) {
		projector.beforeBatch();
		EventReference last = null;
		for ( int i = 0; i < keys.length; i++ ) {
			Event<TestEvent> e = event(keys[i], i + 1, i + 1);
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

	private int totalFor ( String key ) {
		return queryInt("SELECT COALESCE(SUM(total), 0) FROM rm_counting_totals WHERE key_column = '" + key + "'");
	}

	private int rowCount ( ) {
		return queryInt("SELECT COUNT(*) FROM rm_counting_totals");
	}

	private int entryCount ( ) {
		return queryInt("SELECT COUNT(*) FROM rm_counting_entries");
	}

	private int queryInt ( String sql ) {
		try ( var conn = dataSource.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery(sql) ) {
			rs.next();
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

	/**
	 * Projects the same orders through a batch, with references derived from each order's position in
	 * the list — so calling it twice really is a replay of the same events rather than new ones.
	 */
	private void projectOrders ( OrderCountProjector projector, Order... orders ) {
		projector.beforeBatch();
		EventReference last = null;
		for ( int i = 0; i < orders.length; i++ ) {
			EventReference reference = EventReference.of(
					EventId.of(UUID.nameUUIDFromBytes(("order-" + i).getBytes()).toString()), i + 1, i + 1);
			projector.when(Event.of(
					EventStreamId.forContext("test"), reference,
					EventType.of(orders[i]), EventType.of(orders[i]), orders[i],
					Tags.none(), LocalDateTime.now(ZoneOffset.UTC)));
			last = reference;
		}
		projector.afterBatch(Optional.ofNullable(last));
	}

	private int orderCount ( String customer ) {
		return queryInt("SELECT COALESCE(SUM(total_order_count), 0) FROM rm_orders_customers WHERE customer_id = '" + customer + "'");
	}

	private String lastOrderDate ( String customer ) {
		try ( var conn = dataSource.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery("SELECT last_order_date FROM rm_orders_customers WHERE customer_id = '" + customer + "'") ) {
			return rs.next() ? rs.getString(1) : null;
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	record Order ( String customerId, String orderedAt ) { }

	/**
	 * The shape a read model usually has: one row per entity, many events feeding it. Nothing here
	 * maps an event to a row of its own.
	 */
	static class OrderCountProjector extends SqlReadModelProjector<Order> {

		OrderCountProjector ( DataSource dataSource ) {
			super(dataSource, "rm_orders");
		}

		@Override
		protected String[] createTables ( ) {
			return new String[] { """
					CREATE TABLE IF NOT EXISTS %s (
						customer_id VARCHAR(255) NOT NULL PRIMARY KEY,
						total_order_count INT NOT NULL,
						last_order_date VARCHAR(32),
						%s
					)""".formatted(table("customers"), EVENT_REF_COLUMNS) };
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		protected void project ( Event<Order> event ) {
			Order order = event.data();
			insertIfAbsent(table("customers"), "customer_id = ?", new Object[] { order.customerId() },
					"customer_id, total_order_count", order.customerId(), 0);
			updateOnce(table("customers"),
					"total_order_count = total_order_count + 1, last_order_date = ?", new Object[] { order.orderedAt() },
					"customer_id = ?", order.customerId());
		}

	}

	record TestEvent ( String key ) { }

	/**
	 * A read model doing the two things that are not idempotent by themselves: adding to a running
	 * total, and appending a row.
	 */
	static class CountingProjector extends SqlReadModelProjector<TestEvent> {

		CountingProjector ( DataSource dataSource ) {
			super(dataSource, "rm_counting");
		}

		@Override
		protected String[] createTables ( ) {
			return new String[] {
					"""
					CREATE TABLE IF NOT EXISTS %s (
						key_column VARCHAR(255) NOT NULL PRIMARY KEY,
						total INT NOT NULL,
						%s
					)""".formatted(table("totals"), EVENT_REF_COLUMNS),
					"""
					CREATE TABLE IF NOT EXISTS %s (
						key_column VARCHAR(255) NOT NULL,
						%s
					)""".formatted(table("entries"), EVENT_REF_COLUMNS)
			};
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		protected void project ( Event<TestEvent> event ) {
			String key = event.data().key();

			// the row has to exist before it can be incremented, and creating it is idempotent on its
			// own key -- the increment below is what needs the guard
			insertIfAbsent(key);
			updateOnce(table("totals"), "total = total + ?", new Object[] { 1 }, "key_column = ?", key);

			insertOnce(table("entries"), "key_column", key);
		}

		/**
		 * Deliberately UPDATE-then-INSERT rather than {@code INSERT ... WHERE NOT EXISTS}: see
		 * {@link SqlReadModelProjector#insertOnce} for why that shape cannot be trusted here.
		 */
		private void insertIfAbsent ( String key ) {
			int touched = execute("UPDATE %s SET key_column = key_column WHERE key_column = ?".formatted(table("totals")), key);
			if ( touched == 0 ) {
				execute("INSERT INTO %s (key_column, total) VALUES (?, 0)".formatted(table("totals")), key);
			}
		}

	}

}
