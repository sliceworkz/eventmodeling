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
package org.sliceworkz.eventmodeling.module.leadership;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The fencing token travels the whole way: the lease the elector wins carries it, the promotion
 * hands it to the projector's processor, the processor hands it to the read model, and the read
 * model stores it next to its bookmark — in the same transaction as the rows. What the token
 * <em>rejects</em> once stored is pinned by {@code SqlReadModelFencingTest} against the database
 * alone; this test pins the hand-off, because a token that never reaches the bookmark row guards
 * nothing and fails no test on its own.
 *
 * <p>The assertions lean on the lease contract the eventstore's TCK pins per backend: the first
 * owner of a lease gets token 1, and every change of ownership mints a strictly higher one.
 */
public class SqlReadModelFencingHandOffTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final String DOMAIN_PURPOSE = "domain";

	private DataSource readModelDatabase;

	@BeforeEach
	void readModelDatabaseThatOutlivesABoundedContext ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:fencing" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		readModelDatabase = h2;
	}

	@Test
	public void thePromotedProjectorStoresItsLeaseTokenNextToTheBookmark ( ) {
		appendDomainEvents("first-leadership");
		runBoundedContextUntilProjected(1);

		assertEquals(1, storedFencingToken(),
				"the first leadership writes its bookmark under the lease's first token");

		// the leader goes away and a fresh instance takes the lease over: a change of ownership
		// mints a strictly higher token, and the row must follow it -- this growing number is
		// exactly what fences a paused old leader out
		appendDomainEvents("second-leadership");
		runBoundedContextUntilProjected(2);

		assertEquals(2, storedFencingToken(),
				"a failover raises the stored token to the new leadership's");
	}

	// -- driving the bounded context --

	/** Builds a context over the read model, waits for it to catch up, and terminates it. */
	private void runBoundedContextUntilProjected ( int expectedRows ) {
		FencedSqlReadModel readModel = new FencedSqlReadModel(readModelDatabase);
		readModel.ensureTables();

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(readModel).eventuallyConsistent();

		buildBoundedContext(builder);

		// a SHARED read model catches up in the background rather than blocking start(), so this waits
		waitBecauseOfEventualConsistency(() -> projectedRowCount() >= expectedRows);

		releaseBoundedContext();
	}

	private void appendDomainEvents ( String description ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage());
		EventStream<MockDomainEvent> domainEventStream = eventStore.getEventStream(
				EventStreamId.forContext(CONTEXT_NAME).withPurpose(DOMAIN_PURPOSE), MockDomainEvent.class);

		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>();
		events.add(Event.of(new MockDomainEvent.FirstDomainEvent(description), Tags.none()));
		domainEventStream.append(AppendCriteria.none(), events);
	}

	// -- the read model's database --

	private int projectedRowCount ( ) {
		return queryInt("SELECT COUNT(*) FROM rm_fence_entries");
	}

	private long storedFencingToken ( ) {
		return queryInt("SELECT fencing_token FROM rm_fence_projection_bookmark WHERE reader = '"
				+ FencedSqlReadModel.class.getSimpleName() + "'");
	}

	private int queryInt ( String sql ) {
		try ( var conn = readModelDatabase.getConnection();
			  var stmt = conn.createStatement();
			  var rs = stmt.executeQuery(sql) ) {
			rs.next();
			return rs.getInt(1);
		} catch ( SQLException e ) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Declared SHARED although it runs on H2 (the same override {@code SqlReadModelSurvivesRestartTest}
	 * explains): only a SHARED read model is leader-only, and only a leader-only processor is ever
	 * promoted with a token to hand over.
	 */
	static class FencedSqlReadModel extends SqlReadModelProjector<MockDomainEvent> {

		FencedSqlReadModel ( DataSource dataSource ) {
			super(dataSource, "rm_fence");
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
			execute("INSERT INTO %s (description, last_event_id, last_event_position, last_event_tx, last_event_index) VALUES (?, ?, ?, ?, ?)"
					.formatted(table("entries")),
					event.data().toString(), eventId(), eventPosition(), eventTx(), eventIndex());
		}

	}

}
