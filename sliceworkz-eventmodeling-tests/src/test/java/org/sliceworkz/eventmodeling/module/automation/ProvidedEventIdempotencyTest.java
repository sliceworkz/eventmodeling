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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
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
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Raising an event straight from a context — the automation path, where a command carrying its own
 * idempotency key is not involved — had no way to say "this fact, once". An automation is handed the same
 * todo item again whenever the events it raised have not reached its todo list, so without a key every
 * such replay appends the fact a second time.
 */
public class ProvidedEventIdempotencyTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> domainStream;

	@BeforeEach
	void openDomainStream ( ) {
		domainStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"),
						MockDomainEvent.class);
	}

	@ForEachBackend
	void sameKeyStoresTheEventOnce ( ) {
		Mock domain = startPlainContext();

		Optional<EventReference> first = domain.event(new FirstDomainEvent("once"), "key-1");
		Optional<EventReference> second = domain.event(new FirstDomainEvent("once"), "key-1");

		assertTrue(first.isPresent(), "the first append stores the event");
		assertTrue(second.isEmpty(), "a repeat under the same key stores nothing, and says so with an empty reference");
		assertEquals(1, stored(FirstDomainEvent.class), "only one event should have been stored");
	}

	@ForEachBackend
	void differentKeysStoreBothEvents ( ) {
		Mock domain = startPlainContext();

		domain.event(new FirstDomainEvent("one"), "key-1");
		domain.event(new FirstDomainEvent("two"), "key-2");

		assertEquals(2, stored(FirstDomainEvent.class));
	}

	@ForEachBackend
	void theKeyAppliesToTaggedEventsToo ( ) {
		Mock domain = startPlainContext();

		domain.event(new FirstDomainEvent("tagged"), Tags.of("customer", "123"), "key-1");
		domain.event(new FirstDomainEvent("tagged"), Tags.of("customer", "123"), "key-1");

		assertEquals(1, stored(FirstDomainEvent.class));
	}

	@ForEachBackend
	void withoutAKeyTheEventIsStoredTwice ( ) {
		Mock domain = startPlainContext();

		domain.event(new FirstDomainEvent("twice"));
		domain.event(new FirstDomainEvent("twice"));

		assertEquals(2, stored(FirstDomainEvent.class), "no key means no de-duplication, as before");
	}

	/**
	 * The scenario the key exists for, end to end: a todo list that keeps handing back the same item —
	 * which is what a replay after a crash between the append and the bookmark looks like from the
	 * automation's side — and an automation that raises its event under a key derived from the item.
	 */
	@Test
	void anAutomationHandlingAnItemTwiceRaisesItsEventOnce ( ) {
		StickyTodoList todoList = new StickyTodoList("todo-idempotent");
		List<String> handled = Collections.synchronizedList(new ArrayList<>());

		start(todoList, (item, context) -> {
			Optional<EventReference> raised = context.event(new SecondDomainEvent(item), "handled:" + item);
			handled.add(item); // counted after the append, so a second count means a second append has completed
			return raised;
		});

		boundedContext.event(new FirstDomainEvent("item-0"));

		await().atMost(Duration.ofSeconds(40)).untilAsserted(
			() -> assertTrue(handled.size() >= 2, "the item should have been handled more than once, was " + handled.size()));

		assertEquals(1, stored(SecondDomainEvent.class),
			"the event raised for one todo item should be stored once, however often the item is handled");
	}

	private long stored ( Class<? extends MockDomainEvent> type ) {
		return domainStream.query(EventQuery.forEvents(EventTypesFilter.of(type), Tags.none())).count();
	}

	private Mock startPlainContext ( ) {
		return buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests")));
	}

	private void start ( StickyTodoList todoList, Handler handler ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new KeyedAutomation(todoList, handler));
		buildBoundedContext(builder);
	}

	/** Named rather than anonymous: an automation's bookmark is keyed on its simple name. */
	static class KeyedAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final StickyTodoList todoList;
		private final Handler handler;

		KeyedAutomation ( StickyTodoList todoList, Handler handler ) {
			this.todoList = todoList;
			this.handler = handler;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return handler.handle(todoItem, context);
		}
	}

	interface Handler {
		Optional<EventReference> handle ( String item, AutomationContext<MockDomainEvent,MockOutboundEvent> context );
	}

	/**
	 * Projects the events the automation raises — so its bookmark keeps up and the automation is let
	 * round again — but never drops an item, so every round re-handles it.
	 */
	static class StickyTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		StickyTodoList ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.EPHEMERAL;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( event.data() instanceof FirstDomainEvent f ) {
				items.add(f.value());
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			List<String> snapshot = List.copyOf(items);
			int max = (int) Math.min(snapshot.size(), limit.value());
			return IntStream.range(0, max).mapToObj(snapshot::get);
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}
}
