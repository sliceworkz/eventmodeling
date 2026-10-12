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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * The rebuild procedure the admin surface documents — stop the projector, drop the read model's
 * tables, restart the projector — rebuilds an SQL read model inside a running bounded context.
 *
 * <p>Two things have to hold for that, and neither did by accident. The restarted projector has to
 * recreate the tables, since nothing else runs the read model's DDL while the context is up; and it
 * has to resume from the position the read model now records — none, so the beginning — rather than
 * from the in-memory cursor it was stopped with. Both are checked for a read model projected on every
 * instance and for one projected by the elected leader alone, since the two reach a restart by
 * different paths (a parked processor resumed, against a lease handed back and won again).
 */
public class SqlReadModelRebuildTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final int EVENT_COUNT = 25;

	private DataSource readModelDatabase;

	@BeforeEach
	void readModelDatabase ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:rebuild" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		readModelDatabase = h2;
	}

	@Test
	void aReadModelProjectedOnEveryInstanceIsRebuiltByDroppingItsTablesAndRestartingItsProjector ( ) {
		assertTheRebuildProcedureRebuilds(ReadModelStorage.LOCAL);
	}

	@Test
	void aReadModelProjectedByTheLeaderIsRebuiltByDroppingItsTablesAndRestartingItsProjector ( ) {
		assertTheRebuildProcedureRebuilds(ReadModelStorage.SHARED);
	}

	private void assertTheRebuildProcedureRebuilds ( ReadModelStorage storage ) {
		appendDomainEvents();

		RebuildableSqlReadModel readModel = new RebuildableSqlReadModel(readModelDatabase, storage);
		readModel.ensureTables();

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.leadershipIntervals(Duration.ofMillis(200), Duration.ofMillis(800));
		builder.readmodel(readModel).eventuallyConsistent();
		Mock context = buildBoundedContext(builder);

		waitBecauseOfEventualConsistency(() -> projectedRowCount() == EVENT_COUNT);

		String name = readModel.readmodelName();
		assertTrue(context.stopProcessor(ProcessorKind.READ_MODEL, name));
		dropReadModelTables();
		assertTrue(context.restartProcessor(ProcessorKind.READ_MODEL, name));

		waitBecauseOfEventualConsistency(() -> projectedRowCount() == EVENT_COUNT);
		assertEquals(EVENT_COUNT, projectedRowCount(), "the rebuilt read model holds every event exactly once");
	}

	private void appendDomainEvents ( ) {
		EventStore eventStore = EventStore.on(eventStorage()).build();
		EventStream<MockDomainEvent> domainEventStream = eventStore.getEventStream(
				BoundedContextStreams.domain(CONTEXT_NAME), MockDomainEvent.class);

		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>();
		for ( int i = 0; i < EVENT_COUNT; i++ ) {
			events.add(Event.of(new MockDomainEvent.FirstDomainEvent("event " + i), Tags.none()));
		}
		domainEventStream.append(AppendCriteria.none(), events);
	}

	/** -1 while the table is not there, which is the state between the drop and the rebuild. */
	private int projectedRowCount ( ) {
		try ( var conn = readModelDatabase.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery("SELECT COUNT(*) FROM rm_rebuild_entries") ) {
			rs.next();
			return rs.getInt(1);
		} catch ( SQLException e ) {
			return -1;
		}
	}

	private void dropReadModelTables ( ) {
		try ( var conn = readModelDatabase.getConnection();
			  var stmt = conn.createStatement() ) {
			stmt.execute("DROP TABLE rm_rebuild_entries");
			stmt.execute("DROP TABLE rm_rebuild_projection_bookmark");
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	/** Appends a row per event, so a replay that skipped or doubled anything shows in the count. */
	static class RebuildableSqlReadModel extends SqlReadModelProjector<MockDomainEvent> {

		private final ReadModelStorage storage;

		RebuildableSqlReadModel ( DataSource dataSource, ReadModelStorage storage ) {
			super(dataSource, "rm_rebuild");
			this.storage = storage;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return storage;
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
			execute("INSERT INTO %s (description, last_event_id, last_event_position, last_event_tx, last_event_index) VALUES (?, ?, ?, ?, ?)"
					.formatted(table("entries")),
					event.data().toString(), eventId(), eventPosition(), eventTx(), eventIndex());
		}

	}

}
