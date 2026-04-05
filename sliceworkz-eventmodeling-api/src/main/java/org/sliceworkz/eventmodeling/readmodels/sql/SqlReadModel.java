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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Shared base class for SQL-backed read model components.
 *
 * <p>Provides the common infrastructure shared by both the projection side
 * ({@link SqlReadModelProjector}) and the query side ({@link SqlReadModelQuery}):
 * DataSource access, table name prefixing, event reference column definitions,
 * and JDBC parameter helpers.
 *
 * <p>Each read model is assigned a table prefix to namespace its tables and avoid
 * collisions when multiple bounded contexts share the same database.
 */
public abstract class SqlReadModel {

	/**
	 * Standard column definitions for event reference tracking.
	 * Include this snippet in CREATE TABLE statements for tables that track row-level freshness.
	 *
	 * <pre>{@code
	 * CREATE TABLE IF NOT EXISTS %s (
	 *     ...domain columns...,
	 *     %s
	 * )
	 * """.formatted(table("mydata"), EVENT_REF_COLUMNS)
	 * }</pre>
	 */
	protected static final String EVENT_REF_COLUMNS = """
			last_event_id VARCHAR(255) NOT NULL DEFAULT '',
			last_event_position BIGINT NOT NULL DEFAULT 0,
			last_event_tx BIGINT NOT NULL DEFAULT 0,
			last_event_index INT NOT NULL DEFAULT 0""";

	private final DataSource dataSource;
	private final String tablePrefix;

	/**
	 * @param dataSource  the shared read model DataSource
	 * @param tablePrefix namespace prefix for all tables, e.g. {@code "rm_prj"}.
	 *                    Combined with {@link #table(String)} to produce full names
	 *                    like {@code rm_prj_projects}.
	 */
	protected SqlReadModel(DataSource dataSource, String tablePrefix) {
		this.dataSource = dataSource;
		this.tablePrefix = tablePrefix;
	}

	/**
	 * Returns the DataSource used by this read model component.
	 */
	protected DataSource dataSource() {
		return dataSource;
	}

	/**
	 * Returns the fully-qualified table name: {@code tablePrefix + "_" + name}.
	 * Use this in all SQL statements to ensure proper namespacing.
	 *
	 * <p>Example: {@code table("projects")} with prefix {@code "rm_prj"}
	 * produces {@code "rm_prj_projects"}.
	 */
	protected String table(String name) {
		return tablePrefix + "_" + name;
	}

	/**
	 * Reconstructs an {@link EventReference} from the standard columns in a {@link ResultSet}.
	 * Reads {@code last_event_id}, {@code last_event_position}, {@code last_event_tx},
	 * and {@code last_event_index} from the current row.
	 *
	 * @param rs the result set positioned on a row containing the event reference columns
	 */
	protected EventReference readEventReference(ResultSet rs) throws SQLException {
		return EventReference.of(
			EventId.of(rs.getString("last_event_id")),
			rs.getLong("last_event_position"),
			rs.getLong("last_event_tx"),
			rs.getInt("last_event_index")
		);
	}

	/**
	 * Sets positional parameters on a prepared statement.
	 */
	protected void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			stmt.setObject(i + 1, params[i]);
		}
	}
}
