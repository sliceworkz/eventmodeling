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
package org.sliceworkz.eventmodeling.testing.sql;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelQuery;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Base class for testing SQL-backed read models against both H2 and PostgreSQL.
 *
 * <p>Concrete tests subclass this and define their test methods inside a static inner class
 * that extends {@link AbstractSqlReadModelTests}. Two {@code @Nested} implementations —
 * one for H2 and one for Postgres — each override {@code createDataSource()} to run the
 * full test suite against both databases.
 *
 * <p><b>Usage</b>
 * <pre>{@code
 * class MyReadModelTest extends SqlReadModelTest<MyDomainEvent> {
 *
 *     abstract static class Tests extends AbstractSqlReadModelTests<MyDomainEvent> {
 *
 *         @Override protected SqlReadModelProjector<MyDomainEvent> createProjector(DataSource ds) {
 *             return new MyProjector(ds);
 *         }
 *         @Override protected MyQuery createQuery(DataSource ds) {
 *             return new MyQuery(ds);
 *         }
 *         @Override protected String[] tablesToDrop() {
 *             return new String[] { "rm_my_table" };
 *         }
 *
 *         @Test void myTest() {
 *             projectEvents(new SomethingHappened(...));
 *             assertEquals(1, query().getItems().size());
 *         }
 *     }
 *
 *     @Nested class OnH2 extends Tests {
 *         @Override protected DataSource createDataSource() { return h2DataSource(); }
 *     }
 *     @Nested class OnPostgres extends Tests {
 *         @Override protected DataSource createDataSource() { return postgresDataSource(); }
 *     }
 * }
 * }</pre>
 *
 * @param <T> the domain event type
 */
public abstract class SqlReadModelTest<T> {

	/**
	 * The PostgreSQL image {@link #postgresDataSource()} runs against. Deliberately one of the
	 * images {@link PostgresContainer} already manages, so a build that also runs event store
	 * scenarios against PostgreSQL reuses that container rather than starting a second one.
	 */
	private static final String POSTGRES_IMAGE = PostgresContainer.IMAGE_PG17;

	/**
	 * Creates an H2 in-memory DataSource with PostgreSQL compatibility mode.
	 * Each call returns a fresh database.
	 */
	protected static DataSource h2DataSource() {
		JdbcDataSource ds = new JdbcDataSource();
		ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		return ds;
	}

	/**
	 * The pooled DataSource of the shared PostgreSQL testcontainer, started on first use.
	 * <p>
	 * The container and its pool are managed by {@link PostgresContainer} for the lifetime of the
	 * JVM, so nothing here needs starting or closing: per-test isolation comes from
	 * {@link AbstractSqlReadModelTests#tablesToDrop()}, not from a fresh database.
	 */
	protected static DataSource postgresDataSource() {
		return PostgresContainer.dataSource(POSTGRES_IMAGE);
	}

	/**
	 * Abstract base for the actual test methods. Subclass this inside your concrete
	 * test class and provide two {@code @Nested} implementations — one for H2,
	 * one for Postgres — that each override {@link #createDataSource()}.
	 *
	 * @param <T> the domain event type
	 */
	public abstract static class AbstractSqlReadModelTests<T> {

		private SqlReadModelProjector<T> projector;
		private SqlReadModelQuery query;
		private DataSource dataSource;
		private final AtomicLong positionCounter = new AtomicLong(0);
		private final AtomicLong txCounter = new AtomicLong(0);

		/**
		 * Provide a DataSource for this test run.
		 * Use {@link SqlReadModelTest#h2DataSource()} or {@link SqlReadModelTest#postgresDataSource()}.
		 */
		protected abstract DataSource createDataSource();

		/**
		 * Create a fresh instance of the projector under test.
		 */
		protected abstract SqlReadModelProjector<T> createProjector(DataSource dataSource);

		/**
		 * Create a fresh instance of the query side under test.
		 */
		protected abstract SqlReadModelQuery createQuery(DataSource dataSource);

		/**
		 * Return the table names (fully qualified with prefix) that should be dropped
		 * before each test to ensure a clean state. Tables are dropped in the order given,
		 * so list dependent tables before parent tables if foreign keys are used.
		 */
		protected abstract String[] tablesToDrop();

		@BeforeEach
		void setUp() throws Exception {
			positionCounter.set(0);
			txCounter.set(0);
			dataSource = createDataSource();
			try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
				for (String table : tablesToDrop()) {
					stmt.execute("DROP TABLE IF EXISTS " + table);
				}
			}
			projector = createProjector(dataSource);
			projector.ensureTables();
			query = createQuery(dataSource);
		}

		@AfterEach
		void tearDown() {
			// nothing to release: H2 databases are per-test and disposable, and the PostgreSQL pool
			// is owned by PostgresContainer for the lifetime of the JVM
			dataSource = null;
		}

		/**
		 * Returns the query instance under test. Cast to your concrete type.
		 */
		@SuppressWarnings("unchecked")
		protected <Q extends SqlReadModelQuery> Q query() {
			return (Q) query;
		}

		/**
		 * Project one or more domain events through the projector's batch lifecycle.
		 * Events receive auto-incrementing positions and transaction numbers.
		 * <p>
		 * The batch is closed with the reference of the last event, exactly as the framework closes a
		 * real one — which is what makes the projector record its own position here too, so a test
		 * exercises the same path production does.
		 *
		 * @return the reference of the last event projected, or empty if none were given
		 */
		@SafeVarargs
		protected final Optional<EventReference> projectEvents(T... events) {
			projector.beforeBatch();
			EventReference last = null;
			for (T eventData : events) {
				long pos = positionCounter.incrementAndGet();
				long tx = txCounter.incrementAndGet();
				EventReference ref = EventReference.of(EventId.create(), pos, tx);
				Event<T> event = Event.of(
					EventStreamId.forContext("test"),
					ref,
					EventType.of(eventData),
					EventType.of(eventData),
					eventData,
					Tags.none(),
					LocalDateTime.now(ZoneOffset.UTC)
				);
				projector.when(event);
				last = ref;
			}
			Optional<EventReference> lastReference = Optional.ofNullable(last);
			projector.afterBatch(lastReference);
			return lastReference;
		}

		/**
		 * Where the projector under test says it would resume — the position it recorded alongside
		 * the rows it wrote. Empty means it would replay the stream from the beginning.
		 * <p>
		 * Assert on this to prove a read model survives a restart without re-applying what it has
		 * already applied. It reads committed state, so it answers for the batches that landed and
		 * not for one that was rolled back.
		 */
		protected Optional<EventReference> projectedUpTo() {
			return projector.resumeFrom();
		}

		/**
		 * A second projector over the same database, as a restarted instance would build. Use it to
		 * assert that re-projecting events the read model has already seen changes nothing.
		 */
		protected SqlReadModelProjector<T> restartedProjector() {
			SqlReadModelProjector<T> restarted = createProjector(dataSource);
			restarted.ensureTables();
			return restarted;
		}
	}
}
