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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

public class DuplicateAutomationNameTest extends AbstractMockDomainTest {

	@Test
	void duplicateAutomationClassRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder()
				.automation(new MockAutomation("todo-a"))
				.automation(new MockAutomation("todo-b"))
				.build();
		});
		assertEquals("duplicate automation name 'MockAutomation' - bookmarks would collide", e.getMessage());
	}

	/**
	 * An automation's simple name keys its bookmark, so a shape that cannot supply one stably is rejected
	 * where the mistake is made. Both used to fail deeper down with a bare "id is required" that named
	 * neither the automation nor the reason — and the lambda case is the dangerous one, because its
	 * generated name differs between runs, so the bookmark would be new on every start and every todo
	 * item would be handled again.
	 */
	@Test
	void anonymousAutomationRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder().automation(new MockAutomation("todo-anon") { }).build();
		});
		assertTrue(e.getMessage().contains("must be a named class"), e.getMessage());
		assertTrue(e.getMessage().contains("bookmark"), "the message should say why a name is needed: " + e.getMessage());
	}

	@Test
	void anonymousReadModelRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			var builder = baseBuilder();
			builder.readmodel(new MockTodoList("named-so-far") {
				@Override
				public String readmodelName ( ) {
					return getClass().getSimpleName(); // what the default does, and it is blank here
				}
			}).eventuallyConsistent();
			builder.build();
		});
		assertTrue(e.getMessage().contains("must be a named class"), e.getMessage());
	}

	/**
	 * The shape of the class is only held against a read model when it did not name itself: the name is
	 * what keys the bookmark, and an anonymous class returning a stable one of its own supplies that
	 * perfectly well. Rejecting it would break the ordinary "one read model class, several instances
	 * under different names" case the readmodelName() override exists for.
	 */
	@Test
	void anonymousReadModelNamingItselfAccepted ( ) {
		Mock ctx = buildBoundedContext(
			baseBuilder().readmodel(new MockTodoList("anonymous-but-named") { }).eventuallyConsistent()
		);
		assertNotNull(ctx);
	}

	@Test
	void singleAutomationBuildsSuccessfully ( ) {
		Mock ctx = buildBoundedContext(
			baseBuilder().automation(new MockAutomation("solo-todo"))
		);
		assertNotNull(ctx);
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	static class MockAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final String todoName;

		MockAutomation ( String todoName ) {
			this.todoName = todoName;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return new MockTodoList(todoName);
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return Optional.empty();
		}
	}

	static class MockTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;

		MockTodoList ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			// no-op for the test
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return Stream.empty();
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.empty();
		}
	}
}
