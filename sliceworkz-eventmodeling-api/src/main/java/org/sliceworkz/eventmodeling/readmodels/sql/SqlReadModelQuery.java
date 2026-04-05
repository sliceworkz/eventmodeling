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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

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

	// -- Result types --

	/**
	 * Generic wrapper that pairs a query result with the {@link EventReference}
	 * indicating how fresh the data is. Clients can use
	 * {@link EventReference#happenedBefore(EventReference)} to verify freshness.
	 *
	 * @param <R> the result data type
	 */
	public record ReadModelResult<R>(R data, EventReference eventReference) { }

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
}
