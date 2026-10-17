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
package org.sliceworkz.eventmodeling.module.automation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A to-do list kept in a SQL table, projected on one instance and worked by an automation on another:
 * what a deployment that splits the projection aspect from the automation aspect runs.
 * <p>
 * The to-do list is one class, {@link SqlReadModelProjector} and {@link TodoListReadModel} at once, used
 * twice: registered {@code .eventuallyConsistent()} on the projection instance, where its projector
 * writes the table, and handed to the automation on the automation instance, where only its
 * {@link TodoListReadModel#streamItems(Limit) streamItems} is called and reads that table. Being
 * {@code SHARED}, its bookmark is deployment-wide, so nothing at build time asks the automation's
 * instance to project it, and the automation waits on the bookmark the other instance places.
 */
public class SqlTodoListAcrossAspectsTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "SqlTodoListContext";

	private final List<BoundedContext<?,?,?>> contexts = new ArrayList<>();

	private DataSource readModelDatabase;

	@BeforeEach
	void aDatabaseBothInstancesReach ( ) {
		JdbcDataSource h2 = new JdbcDataSource();
		h2.setURL("jdbc:h2:mem:sqltodo" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		readModelDatabase = h2;
	}

	@AfterEach
	void terminateContexts ( ) {
		contexts.forEach(BoundedContext::terminate);
		contexts.clear();
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void anAutomationWorksATodoListProjectedOnAnotherInstance ( ) {
		ItemsToHandle projected = new ItemsToHandle(readModelDatabase);
		projected.ensureTables();
		Mock projection = start(instance("projection").readmodel(projected).eventuallyConsistent());

		ItemsToHandle readOnly = new ItemsToHandle(readModelDatabase);
		HandleItemsAutomation automation = new HandleItemsAutomation(readOnly);
		start(instance("automation").automation(automation));

		appendItems(projection, "a", "b", "c");
		await().atMost(Duration.ofSeconds(20)).until(() -> automation.handled().size() == 3);
		await().atMost(Duration.ofSeconds(20)).until(() -> projected.outstanding().isEmpty());

		// a second wave, after the automation went idle: it must wake on the bookmark the projection
		// instance places, well before its idle poll interval would have it look again
		appendItems(projection, "d", "e");
		await().atMost(Duration.ofSeconds(5)).until(() -> automation.handled().size() == 5);

		assertEquals(List.of("a", "b", "c", "d", "e"), automation.handled(), "every item handled once, in order");
		assertEquals(0, readOnly.projectedEvents(), "the automation instance projects nothing");
		assertEquals(10, projected.projectedEvents(), "the projection instance projects every item and its completion");
	}

	private BoundedContextBuilder<Mock> instance ( String node ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("sqltodo", node));
	}

	private Mock start ( BoundedContextBuilder<Mock> builder ) {
		Mock context = builder.build();
		contexts.add(context);
		context.start();
		return context;
	}

	private static void appendItems ( Mock context, String... items ) {
		for ( String item : items ) {
			context.event(new FirstDomainEvent(item));
		}
	}

	static class HandleItemsAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final ItemsToHandle todoList;
		private final List<String> handled = new CopyOnWriteArrayList<>();

		HandleItemsAutomation ( ItemsToHandle todoList ) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String item, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			handled.add(item);
			return context.event(new SecondDomainEvent(item), "handled-" + item);
		}

		List<String> handled ( ) {
			return List.copyOf(handled);
		}
	}

	/**
	 * An item appears with {@code FirstDomainEvent} and is done with {@code SecondDomainEvent}. Declared
	 * {@code SHARED} although it runs on H2, as {@code SqlReadModelSurvivesRestartTest} explains: a
	 * to-do list on a database every instance reaches.
	 */
	static class ItemsToHandle extends SqlReadModelProjector<MockDomainEvent> implements TodoListReadModel<MockDomainEvent,String> {

		private final AtomicInteger projectedEvents = new AtomicInteger();

		ItemsToHandle ( DataSource dataSource ) {
			super(dataSource, "rm_todo");
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		protected String[] createTables ( ) {
			return new String[] { """
					CREATE TABLE IF NOT EXISTS %s (
						item VARCHAR(255) PRIMARY KEY,
						done BOOLEAN NOT NULL,
						%s
					)""".formatted(table("items"), EVENT_REF_COLUMNS) };
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		protected void project ( Event<MockDomainEvent> event ) {
			projectedEvents.incrementAndGet();
			switch ( event.data() ) {
				case FirstDomainEvent e -> insertOnce(table("items"), "item, done", e.value(), false);
				case SecondDomainEvent e -> updateOnce(table("items"), "done = ?", new Object[] { true }, "item = ?", e.value());
				default -> { }
			}
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return outstanding().stream().limit(limit.isSet() ? limit.value() : Long.MAX_VALUE);
		}

		List<String> outstanding ( ) {
			List<String> items = new ArrayList<>();
			try ( Connection conn = dataSource().getConnection();
				  PreparedStatement stmt = conn.prepareStatement(
						  "SELECT item FROM %s WHERE done = FALSE ORDER BY last_event_tx, last_event_position, last_event_index".formatted(table("items")));
				  ResultSet rs = stmt.executeQuery() ) {
				while ( rs.next() ) {
					items.add(rs.getString(1));
				}
			} catch ( SQLException e ) {
				throw new RuntimeException(e);
			}
			return items;
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.empty();
		}

		int projectedEvents ( ) {
			return projectedEvents.get();
		}
	}
}
