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
	 * DataSource is assumed to point at storage the whole deployment reads and writes, and is
	 * therefore projected by a single leader. Override {@link #storage()} to return
	 * {@link ReadModelStorage#LOCAL} for a database that is durable but private to one instance —
	 * otherwise only the leader's copy is kept up to date.
	 */
	private static ReadModelStorage detectStorage(DataSource dataSource) {
		try {
			var method = dataSource.getClass().getMethod("getURL");
			var url = (String) method.invoke(dataSource);
			return url != null && url.contains(":h2:mem:") ? ReadModelStorage.EPHEMERAL : ReadModelStorage.SHARED;
		} catch (ReflectiveOperationException e) {
			return ReadModelStorage.SHARED;
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
		try {
			batchConnection = dataSource().getConnection();
			batchConnection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new RuntimeException("Failed to start batch transaction", e);
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> lastEventReference) {
		try {
			if (batchConnection != null) {
				batchConnection.commit();
				batchConnection.close();
				batchConnection = null;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to commit batch transaction", e);
		}
	}

	@Override
	public void cancelBatch() {
		try {
			if (batchConnection != null) {
				batchConnection.rollback();
				batchConnection.close();
				batchConnection = null;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to rollback batch transaction", e);
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
