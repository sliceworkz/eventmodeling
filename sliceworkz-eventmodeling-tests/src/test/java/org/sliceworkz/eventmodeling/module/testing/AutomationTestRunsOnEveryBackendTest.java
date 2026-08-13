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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.AutomationTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * That a test written against the <em>published</em> {@link AutomationTest} runs against every
 * registered event storage, not only the in-memory one — the same guarantee
 * {@code CommandTestRunsOnEveryBackendTest} pins for {@code CommandTest}, for the same reason:
 * nothing about a green in-memory run says the other backends were ever asked.
 * <p>
 * The storage-sensitive scenario worth a container here is the redelivery one: an item handled again
 * (the crash-between-append-and-bookmark case) must appended nothing, because the idempotency key
 * derived from the item is deduplicated <em>by the storage</em> — on PostgreSQL by the partial unique
 * index, in memory by the log's own check. The failure-action scenarios are framework behaviour and
 * run as plain {@code @Test}s, once, in memory.
 */
public class AutomationTestRunsOnEveryBackendTest extends AutomationTest<String, MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	/**
	 * The simplest real todo list: a {@code FirstDomainEvent} puts its value on the list, the
	 * {@code SecondDomainEvent} the automation raises takes it off again.
	 */
	static class TodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final Set<String> outstanding = new LinkedHashSet<>();
		private EventReference last;

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			switch ( event.data() ) {
				case FirstDomainEvent first -> outstanding.add(first.value());
				case SecondDomainEvent second -> outstanding.remove(second.value());
				default -> { }
			}
			last = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return List.copyOf(outstanding).stream().limit(limit.value());
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(last);
		}
	}

	/**
	 * Handles an item by recording a {@code SecondDomainEvent} under an item-derived idempotency key
	 * — which is what makes the redelivery scenario a no-op. Items whose value starts with
	 * {@code poison} throw instead, and the failure action is scripted per test.
	 */
	static class TestAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final TodoList todoList = new TodoList();
		private AutomationFailureAction failureAction = AutomationFailureAction.RETRY_ITEM;
		private int batchSize = Automation.DEFAULT_BATCH_SIZE;

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			if ( todoItem.startsWith("poison") ) {
				throw new IllegalStateException("cannot handle " + todoItem);
			}
			return context.event(new SecondDomainEvent(todoItem), "handled:" + todoItem);
		}

		@Override
		public AutomationFailureAction onFailure ( String todoItem, Throwable cause, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return failureAction;
		}

		@Override
		public int batchSize ( ) {
			return batchSize;
		}
	}

	private final TestAutomation automation = new TestAutomation();

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@Override
	public Automation<String,MockDomainEvent,MockOutboundEvent> automation ( ) {
		return automation;
	}

	@ForEachBackend
	void aHappyRoundHandlesTheItemAndTheNextRoundDropsIt ( ) {
		given(new FirstDomainEvent("a"))
			.expectTodoItems("a")
			.whenBatchRuns()
			.itemsHandled(1)
			.events(new SecondDomainEvent("a"))
			.and()
			.expectNoTodoItems();
	}

	@ForEachBackend
	void aRedeliveredItemAppendsNothingBecauseItsKeyIsAlreadyUsed ( ) {
		given(new FirstDomainEvent("a"))
			.whenBatchRuns()
			.events(new SecondDomainEvent("a"))
			.and()
			// the todo list is deliberately not caught up: the previous batch's event has not reached
			// it, so the item is offered again -- the crash-between-append-and-bookmark case
			.whenItemsAreRedelivered()
			.itemsHandled(1)
			.noEvents()
			.and()
			.expectNoTodoItems();
	}

	@Test
	void theDefaultFailureActionHoldsTheItemsBehindAFailingOne ( ) {
		given(new FirstDomainEvent("poison"), new FirstDomainEvent("b"))
			.whenBatchRuns()
			.itemsFailed(1)
			.itemsHandled(0)
			.noEvents()
			.automationStillRunning()
			.and()
			// nothing was handled, so both items are still outstanding, in order
			.expectTodoItems("poison", "b");
	}

	@Test
	void continueAndRetryLaterLetsTheItemsBehindAFailingOneProceed ( ) {
		automation.failureAction = AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER;
		given(new FirstDomainEvent("poison"), new FirstDomainEvent("b"))
			.whenBatchRuns()
			.itemsFailed(1)
			.itemsHandled(1)
			.events(new SecondDomainEvent("b"))
			.and()
			// the failed item is retried on a later batch, the handled one is gone
			.expectTodoItems("poison");
	}

	@Test
	void aStoppedAutomationRefusesFurtherBatchesUntilRestarted ( ) {
		automation.failureAction = AutomationFailureAction.STOP_AUTOMATION;
		var definition = given(new FirstDomainEvent("poison"));

		definition.whenBatchRuns().automationStopped();

		// as in production: a stopped automation does not run again until it is restarted
		assertThrows(AssertionError.class, definition::whenBatchRuns);

		// and restarting without fixing the cause handles the same head item and stops again
		definition.restartAutomation().whenBatchRuns().automationStopped();
	}

	@Test
	void theBatchSizeBoundsOneRound ( ) {
		automation.batchSize = 1;
		given(new FirstDomainEvent("a"), new FirstDomainEvent("b"))
			.whenBatchRuns()
			.itemsHandled(1)
			.events(new SecondDomainEvent("a"))
			.and()
			.whenBatchRuns()
			.itemsHandled(1)
			.events(new SecondDomainEvent("b"))
			.and()
			.expectNoTodoItems();
	}

	@Test
	void aPlainTestStillRunsAgainstTheInMemoryStore ( ) {
		assertNotNull(eventStorage());
		given(new FirstDomainEvent("in-memory"))
			.whenBatchRuns()
			.itemsHandled(1)
			.events(new SecondDomainEvent("in-memory"));
	}

}
