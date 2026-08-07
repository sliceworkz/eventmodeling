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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Read/query side of an SQL-backed read model.
 *
 * <p>Provides JDBC query helper methods for reading projected data. Each query
 * opens its own connection from the DataSource, so it is safe to use concurrently
 * with the projection side and across multiple threads.
 *
 * <p>Subclasses add domain-specific public query methods (e.g. {@code getActiveProjects()})
 * that use the protected helpers. The corresponding projection side should extend
 * {@link SqlReadModelProjector} and share the same table prefix.
 */
public abstract class SqlReadModelQuery extends SqlReadModel {

	/**
	 * @param dataSource  the shared read model DataSource
	 * @param tablePrefix namespace prefix for all tables (must match the projector's prefix)
	 */
	protected SqlReadModelQuery(DataSource dataSource, String tablePrefix) {
		super(dataSource, tablePrefix);
	}

	// -- Row mapping --

	/**
	 * Maps a single row from a {@link ResultSet} to a domain object.
	 *
	 * @param <R> the result type
	 */
	@FunctionalInterface
	protected interface RowMapper<R> {
		/**
		 * Map the current row of the result set to a domain object.
		 *
		 * @param rs the result set positioned on a row
		 * @return the mapped object
		 * @throws SQLException if a database access error occurs
		 */
		R map(ResultSet rs) throws SQLException;
	}

	// -- Query helpers --

	/**
	 * Execute a query and map each row to a result object.
	 */
	protected <R> List<R> queryList(String sql, RowMapper<R> mapper, Object... params) {
		try (var conn = dataSource().getConnection();
			 var stmt = conn.prepareStatement(sql)) {
			setParameters(stmt, params);
			try (var rs = stmt.executeQuery()) {
				List<R> results = new ArrayList<>();
				while (rs.next()) {
					results.add(mapper.map(rs));
				}
				return results;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute readmodel query", e);
		}
	}

	/**
	 * Execute a query, map each row, and return a {@link ReadModelResult} containing the list
	 * and the most recent {@link EventReference} across all rows.
	 *
	 * <p>The SQL must include the standard event reference columns
	 * ({@code last_event_id}, {@code last_event_position}, {@code last_event_tx},
	 * {@code last_event_index}).
	 *
	 * <p><b>The reference reported is a lower bound on how far this read model has been projected</b>,
	 * not that position itself: it is the newest event any <em>returned row</em> reflects, and the
	 * projector may have committed later events that touched other rows — or created rows this query
	 * did not select. Good enough to answer "is my write in this row"; not a base to catch a read
	 * model up from, which is what {@link #loadBaseAt} is for.
	 */
	protected <R> ReadModelResult<List<R>> queryListWithRef(String sql, RowMapper<R> mapper, Object... params) {
		try (var conn = dataSource().getConnection();
			 var stmt = conn.prepareStatement(sql)) {
			setParameters(stmt, params);
			try (var rs = stmt.executeQuery()) {
				List<R> results = new ArrayList<>();
				EventReference latest = null;
				while (rs.next()) {
					results.add(mapper.map(rs));
					EventReference rowRef = readEventReference(rs);
					if (latest == null || latest.happenedBefore(rowRef)) {
						latest = rowRef;
					}
				}
				return new ReadModelResult<>(results, latest);
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute readmodel query", e);
		}
	}

	/**
	 * Execute a query expecting zero or one result.
	 */
	protected <R> Optional<R> querySingle(String sql, RowMapper<R> mapper, Object... params) {
		try (var conn = dataSource().getConnection();
			 var stmt = conn.prepareStatement(sql)) {
			setParameters(stmt, params);
			try (var rs = stmt.executeQuery()) {
				if (rs.next()) {
					return Optional.of(mapper.map(rs));
				}
				return Optional.empty();
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute readmodel query", e);
		}
	}

	/**
	 * Execute a query expecting zero or one result, returning a {@link ReadModelResult}
	 * with the row's {@link EventReference}.
	 *
	 * <p>The SQL must include the standard event reference columns.
	 */
	protected <R> Optional<ReadModelResult<R>> querySingleWithRef(String sql, RowMapper<R> mapper, Object... params) {
		try (var conn = dataSource().getConnection();
			 var stmt = conn.prepareStatement(sql)) {
			setParameters(stmt, params);
			try (var rs = stmt.executeQuery()) {
				if (rs.next()) {
					return Optional.of(new ReadModelResult<>(mapper.map(rs), readEventReference(rs)));
				}
				return Optional.empty();
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute readmodel query", e);
		}
	}

	// -- Loading a base for a seeded read --

	/**
	 * Loads state from this read model's tables, on a connection supplied by
	 * {@link SqlReadModelQuery#loadBaseAt}.
	 *
	 * <p><b>May be called more than once</b>, and each call must <em>replace</em> what the previous
	 * one loaded rather than add to it — see {@code loadBaseAt} for why it retries.
	 */
	@FunctionalInterface
	protected interface BaseLoader {
		void load(Connection conn) throws SQLException;
	}

	/**
	 * How many times {@link #loadBaseAt} re-reads its base before giving up. Reached only when the
	 * projector commits a batch during every single attempt, which a read model being projected at a
	 * normal rate does not do.
	 */
	private static final int MAX_BASE_LOAD_ATTEMPTS = 10;

	/**
	 * Loads a base for a {@link org.sliceworkz.eventmodeling.readmodels.SeededReadModel} together with
	 * the position that base reflects, as one observation, and returns that position.
	 *
	 * <p><b>The problem.</b> The rows and the projector's bookmark are two reads. Whatever the
	 * projector commits between them lands in one and not the other: read the bookmark first and the
	 * caller re-applies events its base already contains, read it afterwards and it skips events that
	 * are in neither. Both produce a wrong answer and neither reports anything.
	 *
	 * <p><b>The answer, and why it is not an isolation level.</b> This reads the bookmark, runs the
	 * loader, reads the bookmark again, and repeats while the two differ. Because
	 * {@link SqlReadModelProjector} commits its rows and its bookmark in one transaction, an unchanged
	 * bookmark proves no batch landed while the base was being read — the rows are exactly at that
	 * position. Which makes this portable and lock-free, where {@code REPEATABLE READ} would have
	 * meant depending on an isolation level PostgreSQL and the H2 an ephemeral read model runs on do
	 * not name or implement alike.
	 *
	 * <p>The retry is why {@link BaseLoader} must overwrite rather than accumulate: a loader that adds
	 * to a list would return that list twice over.
	 *
	 * <p><b>An absent bookmark row means the read model has projected nothing</b>, and is reported as
	 * an empty result — which tells the caller to project the whole stream, correctly, because there
	 * is nothing in those tables to start from. This is the same rule
	 * {@link SqlReadModelProjector#resumeFrom()} follows, for the same reason.
	 *
	 * <p>Note that a missing bookmark <em>table</em> is not that case and is not swallowed: it means
	 * the read model's tables have never been created, and answering "nothing projected" for a read
	 * model whose projector simply has not been built yet would replay the whole stream on every read.
	 *
	 * @param reader the projector's {@code bookmarkReader()} — its {@code readmodelName()} unless it
	 *               overrides that
	 * @param loader loads the base state; called at least once, possibly again on a retry
	 * @return the reference the loaded base reflects, or empty if this read model has projected nothing
	 */
	protected Optional<EventReference> loadBaseAt(String reader, BaseLoader loader) {
		try (var conn = dataSource().getConnection()) {
			for (int attempt = 1; attempt <= MAX_BASE_LOAD_ATTEMPTS; attempt++) {
				Optional<EventReference> before = readBookmark(conn, reader);
				loader.load(conn);
				Optional<EventReference> after = readBookmark(conn, reader);
				if (before.equals(after)) {
					return before;
				}
			}
			throw new IllegalStateException(("Failed to load a base for readmodel '%s' at a stable position: "
					+ "its projector committed a batch during each of %d attempts")
					.formatted(reader, MAX_BASE_LOAD_ATTEMPTS));
		} catch (SQLException e) {
			// never degraded into an empty result: an unreachable database would then be reported as
			// "nothing projected yet", replaying the whole stream into a read model that already holds it
			throw new RuntimeException("Failed to load the base of readmodel " + reader, e);
		}
	}

	/**
	 * Reads the position a read model has been projected up to, from the bookmark row its projector
	 * writes with every batch. Empty when it has projected nothing.
	 */
	protected Optional<EventReference> readBookmark(Connection conn, String reader) throws SQLException {
		String sql = "SELECT last_event_id, last_event_position, last_event_tx, last_event_index FROM %s WHERE reader = ?"
				.formatted(table(BOOKMARK_TABLE));
		try (var stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, reader);
			try (var rs = stmt.executeQuery()) {
				return rs.next() ? Optional.of(readEventReference(rs)) : Optional.empty();
			}
		}
	}
}
