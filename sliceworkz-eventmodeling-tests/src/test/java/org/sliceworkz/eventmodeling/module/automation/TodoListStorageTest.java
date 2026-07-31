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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * An automation waits for the bookmark of the projector that fills its todo list, so both sides have
 * to identify that projector identically. Since both derive the storage class from the todo list
 * itself, this has to hold for every storage class a todo list can declare — it used to be hardcoded
 * to shared on the automation side, which left a local todo list waiting on a bookmark nobody wrote.
 */
public class TodoListStorageTest extends AbstractMockDomainTest {

	@Test
	void automationHandlesItemsOfEphemeralTodoList ( ) {
		assertAutomationHandlesTodoItem(ReadModelStorage.EPHEMERAL);
	}

	@Test
	void automationHandlesItemsOfLocalTodoList ( ) {
		assertAutomationHandlesTodoItem(ReadModelStorage.LOCAL);
	}

	@Test
	void automationHandlesItemsOfSharedTodoList ( ) {
		assertAutomationHandlesTodoItem(ReadModelStorage.SHARED);
	}

	private void assertAutomationHandlesTodoItem ( ReadModelStorage storage ) {
		MockTodoList todoList = new MockTodoList("todo-" + storage.label(), storage);
		MockAutomation automation = new MockAutomation(todoList);

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(automation);

		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("work to do"));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(
			() -> assertEquals(List.of("work to do"), automation.handledItems(),
				"automation should handle the todo item of a %s todo list".formatted(storage.label())));
	}

	static class MockAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final MockTodoList todoList;
		private final List<String> handledItems = Collections.synchronizedList(new ArrayList<>());

		MockAutomation ( MockTodoList todoList ) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			handledItems.add(todoItem);
			todoList.done(todoItem);
			return Optional.empty();
		}

		List<String> handledItems ( ) {
			return List.copyOf(handledItems);
		}
	}

	static class MockTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final ReadModelStorage storage;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		MockTodoList ( String name, ReadModelStorage storage ) {
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
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			items.add(((FirstDomainEvent) event.data()).value());
			lastEventReference = event.reference();
		}

		void done ( String item ) {
			items.remove(item);
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return List.copyOf(items).stream();
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}
}
