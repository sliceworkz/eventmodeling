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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * An automation waits on the bookmark of the projector filling its todo list, and that projector only
 * exists where the todo list is registered {@code eventuallyConsistent()}. Registered alone, the
 * automation would wait for a bookmark nobody writes, silently; {@code build()} rejects it instead.
 * <p>
 * The check is a proof only where the bookmark is scoped to this process (an ephemeral or local todo
 * list). A shared todo list may be projected by another instance of the deployment, so it is not
 * checked. Framework behaviour, not storage behaviour, so plain {@code @Test}s.
 */
public class AutomationTodoListRegistrationTest extends AbstractMockDomainTest {

	@Test
	void anAutomationWhoseEphemeralTodoListIsNotRegisteredIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().automation(new UnregisteredTodoAutomation(new TodoList("todo-unregistered", ReadModelStorage.EPHEMERAL))).build());
		assertEquals("automation registered without a projector for its todo list on this instance: "
				+ "UnregisteredTodoAutomation (its todo list 'todo-unregistered' is ephemeral and not registered: add builder.readmodel(todoList).eventuallyConsistent())",
				e.getMessage());
	}

	@Test
	void anAutomationWhoseLocalTodoListIsNotRegisteredIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().automation(new UnregisteredTodoAutomation(new TodoList("todo-local", ReadModelStorage.LOCAL))).build());
		assertTrue(e.getMessage().contains("UnregisteredTodoAutomation (its todo list 'todo-local' is local and not registered"), e.getMessage());
	}

	@Test
	void everyOffendingAutomationIsNamedAtOnce ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder()
					.automation(new UnregisteredTodoAutomation(new TodoList("todo-one", ReadModelStorage.EPHEMERAL)))
					.automation(new OtherUnregisteredTodoAutomation(new TodoList("todo-two", ReadModelStorage.EPHEMERAL)))
					.build());
		assertTrue(e.getMessage().contains("UnregisteredTodoAutomation (its todo list 'todo-one'"), e.getMessage());
		assertTrue(e.getMessage().contains("OtherUnregisteredTodoAutomation (its todo list 'todo-two'"), e.getMessage());
	}

	/**
	 * The bookmark id carries the storage class, so a read model registered under the todo list's name
	 * with another storage class writes a different bookmark: as much a miss as no registration.
	 */
	@Test
	void aRegistrationUnderTheNameWithAnotherStorageClassIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(new TodoList("todo-mismatch", ReadModelStorage.SHARED)).eventuallyConsistent();
		builder.automation(new UnregisteredTodoAutomation(new TodoList("todo-mismatch", ReadModelStorage.EPHEMERAL)));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("automation registered without a projector for its todo list on this instance: "
				+ "UnregisteredTodoAutomation (its todo list 'todo-mismatch' is ephemeral, but the read model registered under that name is shared: they bookmark under different ids)",
				e.getMessage());
	}

	@Test
	void anAutomationWithoutATodoListIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().automation(new UnregisteredTodoAutomation(null)).build());
		assertEquals("automation registered without a projector for its todo list on this instance: UnregisteredTodoAutomation (getTodoList() returned null)", e.getMessage());
	}

	/**
	 * The registration is matched by name, never by identity: a slice may well construct the todo list
	 * it registers and the one it hands the automation separately.
	 */
	@Test
	void aTodoListRegisteredAsAnotherInstanceUnderTheSameNameIsAccepted ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(new TodoList("todo-by-name", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.automation(new UnregisteredTodoAutomation(new TodoList("todo-by-name", ReadModelStorage.EPHEMERAL)));
		assertNotNull(buildBoundedContext(builder));
	}

	/**
	 * A shared todo list's bookmark is deployment-wide and its projector holds a lease of its own, so
	 * the instance projecting it need not be the one running the automation. Nothing to prove at build
	 * time, so nothing is rejected.
	 */
	@Test
	void anAutomationWhoseSharedTodoListIsNotRegisteredIsAccepted ( ) {
		assertNotNull(buildBoundedContext(
				baseBuilder().automation(new UnregisteredTodoAutomation(new TodoList("todo-shared", ReadModelStorage.SHARED)))));
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	static class UnregisteredTodoAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final TodoList todoList;

		UnregisteredTodoAutomation ( TodoList todoList ) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return Optional.empty();
		}
	}

	static class OtherUnregisteredTodoAutomation extends UnregisteredTodoAutomation {

		OtherUnregisteredTodoAutomation ( TodoList todoList ) {
			super(todoList);
		}
	}

	static class TodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final ReadModelStorage storage;

		TodoList ( String name, ReadModelStorage storage ) {
			this.name = name;
			this.storage = storage;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return storage;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			// nothing to project
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
