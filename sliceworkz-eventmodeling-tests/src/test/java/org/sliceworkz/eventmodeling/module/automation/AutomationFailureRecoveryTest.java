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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * A todo item that fails is not allowed to take the automation down with it. Every failure used to be
 * caught at the batch level and answered by setting the processor to STOPPED, which nothing but a
 * restart of the whole bounded context ever undid — so one bad item, or one ordinary
 * OptimisticLockingException, retired the automation for the life of the process while its items sat
 * outstanding. Failures are contained per item now, and what a failure costs is the automation's own
 * decision through {@link Automation#onFailure}.
 */
public class AutomationFailureRecoveryTest extends AbstractMockDomainTest {

	/**
	 * The default holds the order {@code streamItems} defined: the items behind a failing one wait for it
	 * rather than overtaking it. It costs a poison item at the head of the list holding up the rest, which
	 * is the trade — a stall that shows in {@link org.sliceworkz.eventmodeling.automation.AutomationStatus}
	 * against reordering work that may not be reorderable, silently.
	 */
	@Test
	void theDefaultHoldsTheOrderAndDoesNotStopTheAutomation ( ) {
		TodoList todoList = new TodoList("todo-default-order");
		Handled handled = new Handled();

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			if ( item.equals("bad") ) {
				throw new IllegalStateException("deliberate failure on " + item);
			}
			handled.add(item);
			return context.event(new MockDomainEvent.SecondDomainEvent(item));
		});
		start(todoList, automation);

		boundedContext.event(new FirstDomainEvent("bad"));
		boundedContext.event(new FirstDomainEvent("good-1"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertTrue(automation.attempts() >= 2, "the batch should have been attempted more than once"));

		assertEquals(List.of(), handled.items(),
			"by default the items behind a failing one wait for it rather than overtaking it");
		assertTrue(boundedContext.automations().get(0).running(),
			"stopping a batch does not stop the automation");
	}

	@Test
	void continuingLetsTheItemsBehindAFailingOneProceed ( ) {
		TodoList todoList = new TodoList("todo-failing-item");
		Handled handled = new Handled();

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			if ( item.equals("bad") ) {
				throw new IllegalStateException("deliberate failure on " + item);
			}
			handled.add(item);
			return context.event(new MockDomainEvent.SecondDomainEvent(item));
		});
		automation.failureAction = AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER;
		start(todoList, automation);

		boundedContext.event(new FirstDomainEvent("bad"));
		boundedContext.event(new FirstDomainEvent("good-1"));
		boundedContext.event(new FirstDomainEvent("good-2"));

		// the poison item is at the head of the list, so nothing behind it would be handled at all if a
		// failing item stopped the batch or the processor
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertEquals(List.of("good-1", "good-2"), handled.items(),
				"items behind a failing one should still be handled"));

		// and the automation is still alive afterwards: work arriving later is picked up
		boundedContext.event(new FirstDomainEvent("good-3"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertTrue(handled.items().contains("good-3"),
				"the automation should still handle new work after an item failed"));

		assertTrue(todoList.items().contains("bad"), "the failing item stays outstanding on the todo list");
	}

	/**
	 * There is no inline retry: an item is never handed to {@code handle} twice within one batch. A
	 * transient failure is answered by backing the whole batch off, which is what happens between batches
	 * anyway, and the item is the first thing the next one picks up.
	 */
	@Test
	void aTransientFailureIsRetriedOnTheNextBatch ( ) {
		TodoList todoList = new TodoList("todo-transient");
		Handled handled = new Handled();
		AtomicInteger attempts = new AtomicInteger();

		start(todoList, new TestAutomation(todoList, (item, context) -> {
			if ( attempts.incrementAndGet() == 1 ) {
				throw new EventStorageException("storage briefly unavailable");
			}
			handled.add(item);
			return context.event(new MockDomainEvent.SecondDomainEvent(item));
		}));

		boundedContext.event(new FirstDomainEvent("item-0"));

		await().atMost(Duration.ofSeconds(20)).untilAsserted(
			() -> assertEquals(List.of("item-0"), handled.items(),
				"the item should be handled once the failure has cleared"));

		assertEquals(2, attempts.get(), "the item is attempted once per batch, never twice within one");
	}

	@Test
	void stopAutomationRetiresTheProcessorWhenAskedFor ( ) {
		TodoList todoList = new TodoList("todo-stop-automation");
		Handled handled = new Handled();

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			throw new IllegalStateException("deliberate failure on " + item);
		});
		automation.failureAction = AutomationFailureAction.STOP_AUTOMATION;
		start(todoList, automation);

		boundedContext.event(new FirstDomainEvent("item-0"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertTrue(automation.attempts() >= 1, "the automation should have attempted the item"));

		int afterFirstFailure = automation.attempts();
		sleep(3_000);

		assertEquals(afterFirstFailure, automation.attempts(),
			"an automation asking to be stopped should not attempt any further item");
		assertEquals(List.of(), handled.items());
	}

	@Test
	void handlerProducingNoEventDoesNotSpin ( ) {
		TodoList todoList = new TodoList("todo-no-event");
		AtomicInteger handled = new AtomicInteger();

		start(todoList, new TestAutomation(todoList, (item, context) -> {
			handled.incrementAndGet();
			return Optional.empty(); // "no events were generated", which the API explicitly allows
		}));

		for ( int i = 0; i < Automation.DEFAULT_BATCH_SIZE; i++ ) {
			boundedContext.event(new FirstDomainEvent("item-" + i));
		}

		sleep(3_000);

		// nothing bookmarks a batch that produced no event, so the catch-up guard cannot hold this
		// automation back — it used to go straight round again and re-handle the same full window at
		// full speed (tens of millions of calls in these three seconds). A handful of rounds is expected
		// while the todo list is still being projected, since a todo list that moved is a reason to look
		// again; the bound is set well above that and still four orders of magnitude below a spin
		assertTrue(handled.get() <= 100 * Automation.DEFAULT_BATCH_SIZE,
			"a batch that produced no event should wait rather than re-read the same window, was " + handled.get());
	}

	/**
	 * A batch that bookmarks nothing — every append de-duplicated on its idempotency key, or a handler
	 * that raises nothing — has to fall back on the poll interval, because the catch-up guard has no
	 * reference to hold it against. It must not sit out that interval when the todo list has moved in the
	 * meantime: the bookmark notification is a bare notify(), so one arriving mid-batch used to be lost
	 * and a backlog then advanced one batch per ten seconds.
	 */
	@Test
	void aTodoListChangingDuringABatchIsNotWaitedOut ( ) {
		TodoList todoList = new TodoList("todo-moved-during-batch");
		Handled handled = new Handled();
		AtomicInteger seeded = new AtomicInteger();

		start(todoList, new TestAutomation(todoList, (item, context) -> {
			if ( item.equals("seed") && seeded.incrementAndGet() == 1 ) {
				// put new work on the todo list and make sure it has been projected -- and bookmarked --
				// before this batch ends, so the wake-up for it lands while nothing is waiting for it
				context.event(new FirstDomainEvent("follow-up"));
				await().atMost(Duration.ofSeconds(10)).until(() -> todoList.items().contains("follow-up"));
				sleep(500); // let the projector's bookmark land behind the projection itself
				return Optional.empty(); // bookmarks nothing, exactly as a de-duplicated append would
			}
			handled.add(item);
			return context.event(new MockDomainEvent.SecondDomainEvent(item));
		}));

		boundedContext.event(new FirstDomainEvent("seed"));

		// without remembering that missed wake-up this only happens after the full 10s poll interval
		await().atMost(Duration.ofSeconds(6)).untilAsserted(
			() -> assertTrue(handled.items().contains("follow-up"),
				"work that appeared during a batch should be picked up without waiting out the poll interval"));
	}

	@Test
	void batchSizeOfOneLetsAnItemCancelTheItemsBehindIt ( ) {
		CancellingTodoList todoList = new CancellingTodoList("todo-cancel");
		Handled handled = new Handled();

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			handled.add(item);
			return context.event(new ThirdDomainEvent("everything else is off")); // cancels the rest
		});
		automation.batchSize = 1;
		start(todoList, automation);

		boundedContext.event(new FirstDomainEvent("item-0"));
		boundedContext.event(new FirstDomainEvent("item-1"));
		boundedContext.event(new FirstDomainEvent("item-2"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertEquals(List.of("item-0"), handled.items(), "the first item should be handled"));

		sleep(3_000);

		// with a batch size of one, the processor bookmarks what item-0 produced and waits for the todo
		// list to be projected past it before taking another item — by which time there are none
		assertEquals(List.of("item-0"), handled.items(),
			"items cancelled by the first item should never be handled");
	}

	private void start ( TodoListReadModel<MockDomainEvent,String> todoList, TestAutomation automation ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(automation);
		buildBoundedContext(builder);
	}

	private void sleep ( long ms ) {
		try {
			Thread.sleep(ms);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	static class Handled {
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());

		void add ( String item ) {
			items.add(item);
		}

		List<String> items ( ) {
			return List.copyOf(items);
		}
	}

	interface Handler {
		Optional<EventReference> handle ( String item, AutomationContext<MockDomainEvent,MockOutboundEvent> context );
	}

	static class TestAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final TodoListReadModel<MockDomainEvent,String> todoList;
		private final Handler handler;
		private final AtomicInteger attempts = new AtomicInteger();

		AutomationFailureAction failureAction;
		int batchSize = Automation.DEFAULT_BATCH_SIZE;

		TestAutomation ( TodoListReadModel<MockDomainEvent,String> todoList, Handler handler ) {
			this.todoList = todoList;
			this.handler = handler;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public int batchSize ( ) {
			return batchSize;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			attempts.incrementAndGet();
			return handler.handle(todoItem, context);
		}

		@Override
		public AutomationFailureAction onFailure ( String todoItem, Throwable cause, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return failureAction != null ? failureAction : Automation.super.onFailure(todoItem, cause, context);
		}

		int attempts ( ) {
			return attempts.get();
		}
	}

	/** Items arrive on FirstDomainEvent and are removed again when their SecondDomainEvent is projected. */
	static class TodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		TodoList ( String name ) {
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
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, MockDomainEvent.SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			switch ( event.data() ) {
				case FirstDomainEvent f -> items.add(f.value());
				case MockDomainEvent.SecondDomainEvent s -> items.remove(s.value());
				default -> { }
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			List<String> snapshot = items();
			int max = (int) Math.min(snapshot.size(), limit.value());
			return IntStream.range(0, max).mapToObj(snapshot::get);
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}

		List<String> items ( ) {
			return List.copyOf(items);
		}
	}

	/** Like {@link TodoList}, but a ThirdDomainEvent declares all outstanding work cancelled. */
	static class CancellingTodoList extends TodoList {

		private volatile boolean cancelled;

		CancellingTodoList ( String name ) {
			super(name);
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, MockDomainEvent.SecondDomainEvent.class, ThirdDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( event.data() instanceof ThirdDomainEvent ) {
				cancelled = true;
			}
			super.when(event);
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return cancelled ? Stream.of() : super.streamItems(limit);
		}
	}
}
