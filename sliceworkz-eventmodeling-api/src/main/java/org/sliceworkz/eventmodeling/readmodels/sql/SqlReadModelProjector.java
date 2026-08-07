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
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.readmodels.SelfBookmarkingProjection;
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
 * <h2>How far it has come is kept next to what it produced</h2>
 *
 * <p>Every batch commits the rows it projected <b>and</b> the reference of the last event in that
 * batch, in one transaction, into a bookmark table this class owns. Startup resumes from that
 * reference rather than from the event store's bookmark — see {@link SelfBookmarkingProjection} for
 * why the two cannot be made equivalent. What that buys is worth stating plainly: a crash anywhere
 * leaves the rows and the position agreeing, so no event is ever applied twice and none is skipped.
 *
 * <p>Three practical consequences:
 * <ul>
 *   <li><b>Dropping the tables rebuilds the read model.</b> The bookmark goes with them, so the next
 *       start replays the stream from the beginning. Nothing else has to be reset.</li>
 *   <li><b>The table is created for you</b>, by {@link #ensureTables()} and, failing that, on first
 *       use. It is not part of {@link #createTables()} and must not be declared there.</li>
 *   <li><b>{@link #project(Event)} still ought to be idempotent</b> wherever it cheaply can be. This
 *       mechanism covers the framework's own replay, and leader election keeps a second instance from
 *       projecting a shared read model in steady state — but a failover window is at-least-once (a
 *       leader paused past its lease can finish a batch the new leader repeats), and anything that
 *       re-runs a projection deliberately replays too. See {@link #updateOnce} and
 *       {@link #insertOnce}.</li>
 * </ul>
 *
 * @param <T> the domain event type
 */
public abstract class SqlReadModelProjector<T> extends SqlReadModel implements ReadModelWithMetaData<T>, BatchAwareProjection<T>, SelfBookmarkingProjection {

	private static final Logger LOGGER = LoggerFactory.getLogger(SqlReadModelProjector.class);

	// BOOKMARK_TABLE is on SqlReadModel: the query side reads this same position to know how far the
	// rows it selects have come. A read model may not use that name for a table of its own.

	private final ReadModelStorage storage;
	private final AtomicBoolean bookmarkTableEnsured = new AtomicBoolean();
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
	 * name the implementation happens to use — and pools disagree about that name, so probing one of
	 * them is not enough.
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
	 * Creates the read model's tables if they don't exist, the bookmark table included.
	 * Called once during feature slice configuration.
	 */
	public void ensureTables() {
		ensureBookmarkTable();
		try (var conn = dataSource().getConnection();
			 var statement = conn.createStatement()) {
			for (String ddl : createTables()) {
				statement.execute(ddl);
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to ensure readmodel tables", e);
		}
	}

	/**
	 * Creates the bookmark table if it is not there yet, at most once per projector.
	 * <p>
	 * Called from {@link #ensureTables()} and again from {@link #resumeFrom()}, because the order of
	 * the two is not ours to decide: the bounded context asks a read model where to resume while it
	 * is building its processor, which may be before the feature slice has ensured its tables. The
	 * alternative — asking the database whether the table exists — means identifier-case rules that
	 * differ per database, to avoid a statement that is already a no-op when it is not needed.
	 */
	private void ensureBookmarkTable() {
		if (bookmarkTableEnsured.get()) {
			return;
		}
		try (var conn = dataSource().getConnection();
			 var statement = conn.createStatement()) {
			statement.execute("""
					CREATE TABLE IF NOT EXISTS %s (
						reader VARCHAR(255) NOT NULL PRIMARY KEY,
						%s
					)""".formatted(table(BOOKMARK_TABLE), EVENT_REF_COLUMNS));
		} catch (SQLException e) {
			throw new RuntimeException("Failed to ensure readmodel bookmark table " + table(BOOKMARK_TABLE), e);
		}
		bookmarkTableEnsured.set(true);
	}

	// -- Own bookmark --

	/**
	 * Whether this read model records its own position, which is what makes its projection
	 * exactly-once rather than at-least-once. On by default, and the reason to turn it off is
	 * narrow: a DataSource that may not be issued DDL, or a read model that keeps its position
	 * somewhere else and overrides {@link #afterBatch(Optional)} and {@link #resumeFrom()} itself.
	 * <p>
	 * Turning it off puts the read model back on the framework's bookmark in the event store, which
	 * is written after the projection commits — so a crash in between re-projects a batch, and
	 * anything {@link #project(Event)} does that is not idempotent happens twice.
	 */
	protected boolean tracksItsOwnBookmark() {
		return true;
	}

	/**
	 * The name this read model's bookmark row is keyed by, so two read models sharing a table prefix
	 * do not share a position. Defaults to {@code readmodelName()}, which is what keys the event
	 * store bookmark too.
	 */
	protected String bookmarkReader() {
		return readmodelName();
	}

	@Override
	public Optional<EventReference> resumeFrom() {
		if (!tracksItsOwnBookmark()) {
			return Optional.empty();
		}
		ensureBookmarkTable();
		String sql = "SELECT last_event_id, last_event_position, last_event_tx, last_event_index FROM %s WHERE reader = ?"
				.formatted(table(BOOKMARK_TABLE));
		try (var conn = dataSource().getConnection();
			 var stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, bookmarkReader());
			try (var rs = stmt.executeQuery()) {
				if (!rs.next()) {
					// never projected -- replay from the start of the stream. Deliberately not falling
					// back to the event store's bookmark: that would hand a fresh database a position
					// describing rows it does not have, which is the silent skip this exists to prevent
					return Optional.empty();
				}
				return Optional.of(readEventReference(rs));
			}
		} catch (SQLException e) {
			// never degraded into Optional.empty(): a database that is merely unreachable would then
			// be reported as "nothing projected yet" and replay the whole stream into a full read model
			throw new RuntimeException("Failed to read the bookmark of readmodel " + bookmarkReader(), e);
		}
	}

	/**
	 * Records how far this read model has come, on the batch's own connection so it commits with the
	 * rows the batch produced and can never disagree with them.
	 */
	private void writeBookmark(EventReference reference) {
		String qualifiedTable = table(BOOKMARK_TABLE);
		// UPDATE-then-INSERT rather than an upsert: MERGE, ON CONFLICT and ON DUPLICATE KEY are three
		// different dialects, and a read model is projected by one writer at a time, so there is no
		// race for the portable form to lose
		int updated = execute("""
				UPDATE %s SET last_event_id = ?, last_event_position = ?, last_event_tx = ?, last_event_index = ?
				WHERE reader = ?""".formatted(qualifiedTable),
				reference.id().value(), reference.position(), reference.tx(), reference.index(), bookmarkReader());
		if (updated == 0) {
			execute("""
					INSERT INTO %s (reader, last_event_id, last_event_position, last_event_tx, last_event_index)
					VALUES (?, ?, ?, ?, ?)""".formatted(qualifiedTable),
					bookmarkReader(), reference.id().value(), reference.position(), reference.tx(), reference.index());
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
	 *
	 * <p><b>Write it so that projecting the same event twice leaves the same result.</b> This class
	 * bookmarks itself, so the framework will not replay a committed batch at it — and leader
	 * election keeps a second instance from projecting the same shared read model in steady state.
	 * What remains is the failover window, which is at-least-once by design: a leader paused past
	 * its lease can commit a batch the newly elected leader repeats, and anything that re-runs a
	 * projection deliberately replays too. An UPDATE that sets a column and a DELETE are
	 * idempotent already; an increment, an append and a plain INSERT are not, and
	 * {@link #updateOnce} and {@link #insertOnce} are the two helpers that make them so.
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
			// setAutoCommit fails on a connection already taken from the pool, so it is closed here
			// rather than left to the garbage collector, and the field is not published for a batch
			// that is not going to run
			closeQuietly(connection);
			throw new RuntimeException("Failed to start batch transaction", e);
		}
	}

	/**
	 * Records this batch's position and commits both together.
	 * <p>
	 * The bookmark write is inside the transaction on purpose, and it is the whole point of this
	 * class: the rows and the position they correspond to become durable in one step, so there is no
	 * window in which one is true and the other is not. The framework's own bookmark in the event
	 * store is written after this returns and is left to be a monitoring record.
	 * <p>
	 * A throw from here means the batch did not land, and the projector answers by offering these
	 * events again rather than moving past them — so the failure is deliberately not swallowed.
	 */
	@Override
	public void afterBatch(Optional<EventReference> lastEventReference) {
		if (tracksItsOwnBookmark() && batchConnection != null) {
			lastEventReference.ifPresent(this::writeBookmark);
		}
		endBatch(Connection::commit, "Failed to commit batch transaction");
	}

	@Override
	public void cancelBatch() {
		endBatch(Connection::rollback, "Failed to rollback batch transaction");
	}

	/**
	 * Ends the batch transaction and hands the connection back, whether or not ending it worked. A
	 * projection that cannot commit is one that keeps trying, so a connection held on to here would
	 * drain the pool and bury the read model's real problem under acquisition timeouts elsewhere.
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

	// -- Write helpers --

	/**
	 * Execute a write statement (INSERT, UPDATE, DELETE) within the current batch transaction.
	 *
	 * @return the number of rows affected
	 */
	protected int execute(String sql, Object... params) {
		try (var stmt = batchConnection.prepareStatement(sql)) {
			setParameters(stmt, params);
			return stmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException("Failed to execute projection statement", e);
		}
	}

	// -- Idempotent write helpers --

	/**
	 * An UPDATE that applies at most once per event per row, whatever happens to be replayed.
	 *
	 * <p>Use it for anything that is not naturally idempotent — an increment, an append to a running
	 * total, a counter — where re-applying the same event would silently produce a wrong number
	 * rather than the same one. The target table must carry {@link #EVENT_REF_COLUMNS}; this method
	 * writes them, so the SET clause names domain columns only:
	 *
	 * <pre>{@code
	 * updateOnce(table("accounts"),
	 *            "balance = balance + ?", new Object[] { deposited.amount() },
	 *            "account_id = ?", deposited.accountId());
	 * }</pre>
	 *
	 * <p><b>There is no one-row-per-event assumption here</b>, and the aggregate is the case it is
	 * built for. {@link #EVENT_REF_COLUMNS} on a row means "the newest event this row reflects", not
	 * "the event that produced this row", so a table of
	 * {@code (customer_id, total_order_count, last_order_date)} fed by a stream of {@code OrderReceived}
	 * works exactly as you would want: every order advances its customer's row and its recorded
	 * position, and a replayed order is recognised against that position and skipped. One event
	 * updating many rows is equally fine — each row is guarded on its own.
	 *
	 * <p><b>What it cannot do is create the row</b>, because an UPDATE matching nothing updates
	 * nothing. Pair it with {@link #insertIfAbsent}, which is written to leave the freshness columns
	 * at their zero defaults precisely so the update that follows still applies:
	 *
	 * <pre>{@code
	 * insertIfAbsent(table("customers"), "customer_id = ?", new Object[] { received.customerId() },
	 *                "customer_id, total_order_count", received.customerId(), 0);
	 * updateOnce(table("customers"),
	 *            "total_order_count = total_order_count + 1, last_order_date = ?",
	 *            new Object[] { received.orderedAt() },
	 *            "customer_id = ?", received.customerId());
	 * }</pre>
	 *
	 * <p><b>The comparison is the total {@code (tx, position, index)} order, never the position
	 * alone.</b> A position is a {@code bigserial} and a transaction id an {@code xid8}, assigned
	 * independently, so an event can hold a lower position and a higher transaction than one that
	 * committed before it — the same reason
	 * {@link org.sliceworkz.eventstore.events.EventReference#happenedAfter} exists and the DCB check
	 * is written the way it is. A guard on {@code last_event_position} alone would discard events it
	 * should apply, which is worse than the duplication it was meant to prevent, and would do it
	 * rarely enough to reach production.
	 *
	 * @param table       the fully qualified table name, from {@link #table(String)}
	 * @param setClause   the SET assignments for the domain columns, with {@code ?} placeholders
	 * @param setParams   the parameters for {@code setClause}, in order
	 * @param whereClause the rows to update, with {@code ?} placeholders. It is bracketed before the
	 *                    freshness guard is appended, so an {@code OR} in it stays contained
	 * @param whereParams the parameters for {@code whereClause}, in order
	 * @return the number of rows updated, which is 0 when every matching row has already seen this
	 *         event
	 */
	protected int updateOnce(String table, String setClause, Object[] setParams, String whereClause, Object... whereParams) {
		String sql = """
				UPDATE %s SET %s, last_event_id = ?, last_event_position = ?, last_event_tx = ?, last_event_index = ?
				WHERE (%s) AND %s""".formatted(table, setClause, whereClause, notYetAppliedPredicate());

		Object[] params = new Object[setParams.length + 4 + whereParams.length + 5];
		int at = 0;
		for (Object param : setParams) {
			params[at++] = param;
		}
		params[at++] = eventId();
		params[at++] = eventPosition();
		params[at++] = eventTx();
		params[at++] = eventIndex();
		for (Object param : whereParams) {
			params[at++] = param;
		}
		for (Object param : notYetAppliedParameters()) {
			params[at++] = param;
		}
		return execute(sql, params);
	}

	/**
	 * Creates a row if no row matches, for the aggregate that has to exist before it can be updated.
	 *
	 * <p>The companion to {@link #updateOnce}: create the row, then apply the event to it. Both
	 * halves survive a replay — the insert because the row is then already there, the update because
	 * of its own freshness guard.
	 *
	 * <p><b>It deliberately does not write {@link #EVENT_REF_COLUMNS}</b>, leaving them at their zero
	 * defaults, which means "this row has seen no event yet". Writing the current event's reference
	 * here instead would be the natural-looking mistake and a nasty one: the very next
	 * {@code updateOnce} would find the row already marked with that event and skip it, so the first
	 * order of every new customer would create the row and never be counted.
	 *
	 * <p>Where the row's initial values already account for the event — an insert of
	 * {@code (customer_id, total_order_count) VALUES (?, 1)} rather than {@code 0} followed by an
	 * increment — use {@link #insertOnce} instead, which does record the reference. Mixing the two up
	 * is the one way to get this wrong, so prefer inserting a neutral row and letting
	 * {@code updateOnce} do all the arithmetic.
	 *
	 * @param table       the fully qualified table name, from {@link #table(String)}
	 * @param keyClause   what identifies the row, with {@code ?} placeholders
	 * @param keyParams   the parameters for {@code keyClause}, in order
	 * @param columns     the domain columns to insert, comma separated
	 * @param values      one value per named column, in order
	 * @return 1 when the row was created, 0 when it was already there
	 */
	protected int insertIfAbsent(String table, String keyClause, Object[] keyParams, String columns, Object... values) {
		// SELECT then INSERT, for the reason given on insertOnce
		try (var stmt = batchConnection.prepareStatement("SELECT 1 FROM %s WHERE %s".formatted(table, keyClause))) {
			setParameters(stmt, keyParams);
			try (var rs = stmt.executeQuery()) {
				if (rs.next()) {
					return 0;
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to check for an existing row in " + table, e);
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < values.length; i++) {
			placeholders.append(i == 0 ? "?" : ", ?");
		}
		return execute("INSERT INTO %s (%s) VALUES (%s)".formatted(table, columns, placeholders), values);
	}

	/**
	 * An INSERT that is ignored if this event already produced its row, for the append-style read
	 * model — a log, a list, a dead-letter view — where a replayed event would otherwise add a
	 * second copy.
	 *
	 * <p>The target table must carry {@link #EVENT_REF_COLUMNS}; this method writes them, so name
	 * only the domain columns:
	 *
	 * <pre>{@code
	 * insertOnce(table("entries"), "entry_id, description", entryId, description);
	 * }</pre>
	 *
	 * <p>Recognising the repeat by {@code last_event_id} rather than by the domain key is what makes
	 * it work for a table where two different events legitimately produce identical-looking rows.
	 * <b>One event inserting several rows is the case this does not cover</b> — the second row looks
	 * like the repeat of the first — so give those a unique domain key and use
	 * {@link #execute(String, Object...)} with a constraint instead.
	 *
	 * <p>It is a SELECT and then an INSERT rather than the single
	 * {@code INSERT ... SELECT ... WHERE NOT EXISTS} it would like to be, and that is not stylistic.
	 * H2 — which is what an ephemeral read model runs on — caches the result of that subquery across
	 * executions of the same statement within a transaction, so the second call inserts a row the
	 * first one had already inserted and the statement dies on the unique index. Two statements have
	 * no such trap, and they need no dialect-specific upsert (`MERGE`, `ON CONFLICT`,
	 * `ON DUPLICATE KEY`) either. Nothing is lost by the split: a read model is projected by one
	 * writer at a time, so there is no race between them to lose.
	 *
	 * @param table   the fully qualified table name, from {@link #table(String)}
	 * @param columns the domain columns to insert, comma separated
	 * @param values  one value per named column, in order
	 * @return 1 when the row was inserted, 0 when this event had already produced it
	 */
	protected int insertOnce(String table, String columns, Object... values) {
		if (alreadyProduced(table)) {
			return 0;
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < values.length; i++) {
			placeholders.append(i == 0 ? "?" : ", ?");
		}
		String sql = """
				INSERT INTO %s (%s, last_event_id, last_event_position, last_event_tx, last_event_index)
				VALUES (%s, ?, ?, ?, ?)""".formatted(table, columns, placeholders);

		Object[] params = new Object[values.length + 4];
		int at = 0;
		for (Object value : values) {
			params[at++] = value;
		}
		params[at++] = eventId();
		params[at++] = eventPosition();
		params[at++] = eventTx();
		params[at] = eventIndex();
		return execute(sql, params);
	}

	/** Whether the event being projected has already put a row into this table. */
	private boolean alreadyProduced(String table) {
		try (var stmt = batchConnection.prepareStatement("SELECT 1 FROM %s WHERE last_event_id = ?".formatted(table))) {
			stmt.setString(1, eventId());
			try (var rs = stmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to check whether event %s was already projected into %s".formatted(eventId(), table), e);
		}
	}

	/**
	 * "This row has not seen the event being projected", as a predicate over the total
	 * {@code (tx, position, index)} order that reads and writes agree on.
	 * <p>
	 * Spelled out rather than written as the row-value comparison {@code (a, b, c) < (?, ?, ?)},
	 * which PostgreSQL supports and not every database this may run against does. It expects the
	 * five parameters {@link #notYetAppliedParameters()} supplies, in that order.
	 */
	private static String notYetAppliedPredicate() {
		return "( last_event_tx < ? OR ( last_event_tx = ? AND ( last_event_position < ? OR "
				+ "( last_event_position = ? AND last_event_index < ? ) ) ) )";
	}

	/** The five parameters {@link #notYetAppliedPredicate()} expects, for the event being projected. */
	private Object[] notYetAppliedParameters() {
		return new Object[] { eventTx(), eventTx(), eventPosition(), eventPosition(), eventIndex() };
	}
}
