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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A durable SQL read model does not re-project what it has already committed, even when the
 * framework's own bookmark says it should.
 *
 * <p>This is the crash window, reproduced. A read model commits its rows in its own database and the
 * framework bookmarks its progress in the event store — two stores, no transaction between them, and
 * the bookmark written second. A process that dies in that window comes back to a bookmark that is
 * behind the rows, and re-applies events the read model already holds: duplicated inserts and
 * double-counted totals in a durable, shared read model, permanently, with nothing raised anywhere.
 *
 * <p>Removing the bookmark outright is that window taken to its limit, and it is the honest way to
 * test it: nothing about a crash is reproducible, but the state it leaves behind is exactly "the
 * read model is ahead of its bookmark". What has to hold is that the read model resumes from what it
 * recorded next to its own rows, and does not consult the event store's bookmark at all.
 */
public class SqlReadModelSurvivesRestartTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final String DOMAIN_PURPOSE = "domain";
	private static final int EVENT_COUNT = 25;

	private DataSource readModelDatabase;

	@BeforeEach
	void readModelDatabaseThatOutlivesABoundedContext ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		// DB_CLOSE_DELAY=-1: the database has to survive the bounded context that was using it, which
		// is the whole point -- a durable read model is one whose rows are still there on restart
		h2.setURL("jdbc:h2:mem:restart" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		readModelDatabase = h2;
	}

	@Test
	void aRestartedReadModelDoesNotReprojectWhatItHasAlreadyCommitted ( ) {
		appendDomainEvents();

		runBoundedContextUntilProjected();
		assertEquals(EVENT_COUNT, projectedRowCount(), "everything in the stream reached the read model");

		// the crash window: the rows are committed, the framework's bookmark is not
		assertFalse(removeFrameworkBookmarks().isEmpty(), "the framework did bookmark this read model");

		runBoundedContextUntilProjected();

		assertEquals(EVENT_COUNT, projectedRowCount(),
				"the restarted read model resumed from its own position instead of replaying the stream into itself");
	}

	/**
	 * And a read model whose database really is gone does replay — the position lives with the rows,
	 * so the two cannot disagree about whether anything has been projected.
	 */
	@Test
	void aReadModelThatLostItsDatabaseReplaysTheStream ( ) {
		appendDomainEvents();

		runBoundedContextUntilProjected();
		assertEquals(EVENT_COUNT, projectedRowCount());

		// the framework's bookmark is deliberately left in place, naming the end of the stream. It must
		// not be what decides: the read model's own storage says it holds nothing, and that wins
		dropReadModelTables();

		runBoundedContextUntilProjected();

		assertEquals(EVENT_COUNT, projectedRowCount(),
				"an emptied read model rebuilds itself from the start of the stream");
	}

	// -- driving the bounded context --

	/**
	 * Builds a bounded context over the read model, waits for it to catch up, and terminates it —
	 * one process lifetime, so that calling it twice is a restart.
	 */
	private void runBoundedContextUntilProjected ( ) {
		RestartingSqlReadModel readModel = new RestartingSqlReadModel(readModelDatabase);
		readModel.ensureTables();

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(readModel).eventuallyConsistent();

		buildBoundedContext(builder);

		// a SHARED read model catches up in the background rather than blocking start(), so this waits
		waitBecauseOfEventualConsistency(() -> projectedRowCount() >= EVENT_COUNT);

		releaseBoundedContext();
	}

	private void appendDomainEvents ( ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage());
		EventStream<MockDomainEvent> domainEventStream = eventStore.getEventStream(
				EventStreamId.forContext(CONTEXT_NAME).withPurpose(DOMAIN_PURPOSE), MockDomainEvent.class);

		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>();
		for ( int i = 0; i < EVENT_COUNT; i++ ) {
			events.add(Event.of(new MockDomainEvent.FirstDomainEvent("event " + i), Tags.none()));
		}
		domainEventStream.append(AppendCriteria.none(), events);
	}

	/** @return the reader names removed, so a test can assert there was something to remove */
	private List<String> removeFrameworkBookmarks ( ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage());
		EventStream<MockDomainEvent> domainEventStream = eventStore.getEventStream(
				EventStreamId.forContext(CONTEXT_NAME).withPurpose(DOMAIN_PURPOSE), MockDomainEvent.class);

		List<String> removed = new ArrayList<>();
		domainEventStream.getBookmarks().stream()
				.map(bookmark -> bookmark.reader())
				.filter(reader -> reader.contains(RestartingSqlReadModel.class.getSimpleName()))
				.forEach(reader -> {
					domainEventStream.removeBookmark(reader);
					removed.add(reader);
				});
		return removed;
	}

	// -- the read model's database --

	private int projectedRowCount ( ) {
		try ( var conn = readModelDatabase.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery("SELECT COUNT(*) FROM rm_restart_entries") ) {
			rs.next();
			return rs.getInt(1);
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	private void dropReadModelTables ( ) {
		try ( var conn = readModelDatabase.getConnection();
			  var stmt = conn.createStatement() ) {
			stmt.execute("DROP TABLE rm_restart_entries");
			stmt.execute("DROP TABLE rm_restart_projection_bookmark");
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * A read model that appends a row per event — the shape that makes a re-projection visible, since
	 * an overwriting one would look identical whether or not it happened twice.
	 * <p>
	 * Declared SHARED because that is what a durable read model is, even though this one happens to
	 * run on H2: without the override, the DataSource would be read as an in-memory database and the
	 * read model treated as ephemeral.
	 */
	static class RestartingSqlReadModel extends SqlReadModelProjector<MockDomainEvent> {

		RestartingSqlReadModel ( DataSource dataSource ) {
			super(dataSource, "rm_restart");
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		protected String[] createTables ( ) {
			return new String[] { """
					CREATE TABLE IF NOT EXISTS %s (
						description VARCHAR(255) NOT NULL,
						%s
					)""".formatted(table("entries"), EVENT_REF_COLUMNS) };
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		protected void project ( Event<MockDomainEvent> event ) {
			// deliberately a plain insert with no idempotency of its own: what is being tested is that
			// the framework does not hand this event over twice, not that the read model copes when it does
			execute("INSERT INTO %s (description, last_event_id, last_event_position, last_event_tx, last_event_index) VALUES (?, ?, ?, ?, ?)"
					.formatted(table("entries")),
					event.data().toString(), eventId(), eventPosition(), eventTx(), eventIndex());
		}

	}

}
