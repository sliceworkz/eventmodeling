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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;

/**
 * Write/projection side of an SQL-backed read model.
 *
 * <p>Handles the batch-aware projection lifecycle (connection management, transactions),
 * table creation, and event reference tracking. This class is registered with the
 * framework as a {@link ReadModelWithMetaData} and {@link BatchAwareProjection} so
 * events are delivered to it automatically.
 *
 * <p>Subclasses implement:
 * <ul>
 *   <li>{@link #createTables()} — DDL statements (use {@link #table(String)} for table names)</li>
 *   <li>{@link ReadModelWithMetaData#eventQuery()} — which events to subscribe to</li>
 *   <li>{@link #project(Event)} — event projection logic</li>
 * </ul>
 *
 * <p>The corresponding query side should extend {@link SqlReadModelQuery} and share
 * the same table prefix so both operate on the same tables.
 *
 * @param <T> the domain event type
 */
public abstract class SqlReadModelProjector<T> extends SqlReadModel implements ReadModelWithMetaData<T>, BatchAwareProjection<T> {

	private static final Logger LOGGER = LoggerFactory.getLogger(SqlReadModelProjector.class);

	private final ReadModelStorage storage;
	private Connection batchConnection;
	private EventReference currentEventReference;

	/**
	 * @param dataSource  the shared read model DataSource
	 * @param tablePrefix namespace prefix for all tables
	 */
	protected SqlReadModelProjector(DataSource dataSource, String tablePrefix) {
		super(dataSource, tablePrefix);
		this.storage = detectStorage(dataSource);
	}

	@Override
	public ReadModelStorage storage() {
		return storage;
	}

	/**
	 * An in-memory H2 database dies with the process, so such a read model is ephemeral. Any other
	 * DataSource — and any whose URL cannot be determined — is assumed to point at storage the whole
	 * deployment reads and writes, and is therefore projected by a single leader. Override
	 * {@link #storage()} to return {@link ReadModelStorage#LOCAL} for a database that is durable but
	 * private to one instance — otherwise only the leader's copy is kept up to date.
	 * <p>
	 * {@code SHARED} is the deliberate fallback rather than {@code EPHEMERAL}, because the two ways of
	 * being wrong do not cost the same: a shared read model mistaken for an ephemeral one has its
	 * bookmark dropped on every start and reprojects its whole history into a durable database, which
	 * duplicates rows. The other way round only leaves a follower's copy unfilled.
	 */
	private static ReadModelStorage detectStorage(DataSource dataSource) {
		String url = jdbcUrlOf(dataSource);
		return url != null && url.contains(":h2:mem:") ? ReadModelStorage.EPHEMERAL : ReadModelStorage.SHARED;
	}

	/**
	 * The JDBC URL behind a DataSource, or {@code null} when it cannot be established.
	 * <p>
	 * There is no accessor for this on {@link DataSource} itself, so the URL has to be asked for by a
	 * name the implementation happens to use. Probing only {@code getURL} — the name the JDK's own
	 * {@code JdbcDataSource} and H2's use — missed the pooled case entirely: HikariCP calls it
	 * {@code getJdbcUrl}, so every Hikari-pooled read model, in-memory H2 included, fell into the
	 * fallback and was classified {@code SHARED}. That is silent and it matters: the read model becomes
	 * leader-only, so no other instance ever fills its own copy, and its bookmark stops being dropped
	 * at startup.
	 * <p>
	 * The known getter names are tried first because they need no database. Only when none of them
	 * exists is a connection taken and asked for {@link java.sql.DatabaseMetaData#getURL()}, which is
	 * authoritative and sees through any wrapper — at the cost of requiring the database to be
	 * reachable, so it is the last resort rather than the first.
	 */
	private static String jdbcUrlOf(DataSource dataSource) {
		// getJdbcUrl: HikariCP, c3p0. getURL: H2, the JDK's own JdbcDataSource, PGSimpleDataSource.
		// getUrl: Commons DBCP, Tomcat JDBC.
		for (String getter : new String[] { "getJdbcUrl", "getURL", "getUrl" }) {
			try {
				Object url = dataSource.getClass().getMethod(getter).invoke(dataSource);
				if (url instanceof String string) {
					return string;
				}
			} catch (ReflectiveOperationException | RuntimeException notThisOne) {
				// try the next name
			}
		}
		try (var connection = dataSource.getConnection()) {
			return connection.getMetaData().getURL();
		} catch (SQLException | RuntimeException e) {
			LOGGER.warn("cannot determine the JDBC URL of {}, assuming its read models are SHARED - override storage() if that is wrong",
					dataSource.getClass().getName(), e);
			return null;
		}
	}

	// -- Schema --

	/**
	 * Subclasses return their DDL statements (CREATE TABLE IF NOT EXISTS ...).
	 */
	protected abstract String[] createTables();

	/**
	 * Creates the read model's tables if they don't exist.
	 * Called once during feature slice configuration.
	 */
	public void ensureTables() {
		try (var conn = dataSource().getConnection();
			 var statement = conn.createStatement()) {
			for (String ddl : createTables()) {
				statement.execute(ddl);
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to ensure readmodel tables", e);
		}
	}

	// -- Event reference tracking --

	/**
	 * Returns the full {@link EventReference} of the event currently being projected.
	 * Only valid during a {@link #project(Event)} call.
	 */
	protected EventReference eventReference() {
		return currentEventReference;
	}

	/**
	 * Returns the event ID (as String) of the event currently being projected.
	 */
	protected String eventId() {
		return currentEventReference.id().value();
	}

	/**
	 * Returns the global position of the event currently being projected.
	 */
	protected long eventPosition() {
		return currentEventReference.position();
	}

	/**
	 * Returns the transaction number of the event currently being projected.
	 */
	protected long eventTx() {
		return currentEventReference.tx();
	}

	/**
	 * Returns the sub-event index of the event currently being projected.
	 */
	protected int eventIndex() {
		return currentEventReference.index();
	}

	// -- Projection --

	/**
	 * Intercepts the framework's {@code when()} to capture the full event reference,
	 * then delegates to {@link #project(Event)}.
	 */
	@Override
	public final void when(Event<T> event) {
		currentEventReference = event.reference();
		project(event);
	}

	/**
	 * Project a single event into the read model's SQL tables.
	 * Use {@link #execute(String, Object...)} for writes and
	 * {@link #eventId()}, {@link #eventPosition()}, {@link #eventTx()}, {@link #eventIndex()}
	 * for the current event's reference columns.
	 */
	protected abstract void project(Event<T> event);

	// -- BatchAwareProjection lifecycle --

	@Override
	public void beforeBatch() {
		Connection connection = null;
		try {
			connection = dataSource().getConnection();
			connection.setAutoCommit(false);
			batchConnection = connection;
		} catch (SQLException e) {
			// setAutoCommit is the one that realistically fails here, and it fails on a connection we
			// have already taken from the pool - so it is closed rather than left to the garbage
			// collector, and the field is not published for a batch that is not going to run
			closeQuietly(connection);
			throw new RuntimeException("Failed to start batch transaction", e);
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> lastEventReference) {
		endBatch(Connection::commit, "Failed to commit batch transaction");
	}

	@Override
	public void cancelBatch() {
		endBatch(Connection::rollback, "Failed to rollback batch transaction");
	}

	/**
	 * Ends the batch transaction and hands the connection back, whether or not ending it worked.
	 * <p>
	 * The commit and the close used to be consecutive statements inside one try, so a commit that threw
	 * skipped the close and left {@code batchConnection} pointing at an open connection that the next
	 * {@code beforeBatch} then overwrote. That leaks one pooled connection per failed commit — and a
	 * projection failing to commit is exactly the situation that repeats, so the pool drains and the
	 * read model's real problem is buried under connection-acquisition timeouts.
	 */
	private void endBatch(BatchEnding ending, String failureMessage) {
		Connection connection = batchConnection;
		if (connection == null) {
			return;
		}
		// cleared before we try: whatever happens below, this connection is not the next batch's
		batchConnection = null;
		try (Connection closing = connection) {
			ending.end(closing);
		} catch (SQLException e) {
			throw new RuntimeException(failureMessage, e);
		}
	}

	/** How a batch transaction is concluded — {@link Connection#commit} or {@link Connection#rollback}. */
	@FunctionalInterface
	private interface BatchEnding {
		void end(Connection connection) throws SQLException;
	}

	private static void closeQuietly(Connection connection) {
		if (connection != null) {
			try {
				connection.close();
			} catch (SQLException ignored) {
				// we are already reporting the failure that got us here
			}
		}
	}

	// -- Write helper --

	/**
	 * Execute a write statement (INSERT, UPDATE, DELETE) within the current batch transaction.
	 */
	protected void execute(String sql, Object... params) {
		try (var stmt = batchConnection.prepareStatement(sql)) {
			setParameters(stmt, params);
			stmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute projection statement", e);
		}
	}
}
