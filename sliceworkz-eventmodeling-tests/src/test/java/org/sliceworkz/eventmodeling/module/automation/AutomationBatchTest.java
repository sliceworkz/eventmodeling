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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * The batch loop itself, driven directly. These are the semantics {@code AutomationProcessor} runs in
 * production and the published {@code AutomationTest} harness runs on the test thread — extracted into
 * {@code AutomationBatch} precisely so the two cannot diverge, and pinned here once instead of once per
 * caller. The end-to-end behaviour through a running processor stays pinned by
 * {@code AutomationFailureRecoveryTest}.
 */
public class AutomationBatchTest {

	private static EventReference reference ( long position ) {
		return EventReference.of(EventId.create(), position, position);
	}

	/** A todo list serving a fixed set of items, recording when each is pulled. */
	private static class ScriptedTodoList implements TodoListReadModel<Object,String> {

		private final List<String> items;
		private final List<String> interactions;
		private Limit lastRequestedLimit;

		ScriptedTodoList ( List<String> items, List<String> interactions ) {
			this.items = items;
			this.interactions = interactions;
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			lastRequestedLimit = limit;
			return items.stream().limit(limit.value()).peek(item -> interactions.add("pulled:" + item));
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.empty();
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchNone();
		}

		@Override
		public void when ( Event<Object> event ) {
			// nothing to project: the items are scripted
		}
	}

	/** An automation whose handling and failure policy are scripted per item. */
	private static class ScriptedAutomation implements Automation<String,Object,Object> {

		private final ScriptedTodoList todoList;
		private final Function<String,Optional<EventReference>> handler;
		private final BiFunction<String,Throwable,AutomationFailureAction> failurePolicy;
		private final List<String> interactions;
		private int batchSize = Automation.DEFAULT_BATCH_SIZE;

		ScriptedAutomation ( ScriptedTodoList todoList, List<String> interactions, Function<String,Optional<EventReference>> handler, BiFunction<String,Throwable,AutomationFailureAction> failurePolicy ) {
			this.todoList = todoList;
			this.interactions = interactions;
			this.handler = handler;
			this.failurePolicy = failurePolicy;
		}

		@Override
		public TodoListReadModel<Object,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<Object,Object> context ) {
			interactions.add("handled:" + todoItem);
			return handler.apply(todoItem);
		}

		@Override
		public int batchSize ( ) {
			return batchSize;
		}

		@Override
		public AutomationFailureAction onFailure ( String todoItem, Throwable cause, AutomationContext<Object,Object> context ) {
			return failurePolicy.apply(todoItem, cause);
		}
	}

	private final List<String> interactions = new ArrayList<>();

	private ScriptedAutomation automation ( List<String> items, Function<String,Optional<EventReference>> handler, BiFunction<String,Throwable,AutomationFailureAction> failurePolicy ) {
		return new ScriptedAutomation(new ScriptedTodoList(items, interactions), interactions, handler, failurePolicy);
	}

	private AutomationBatch.Outcome runBatch ( ScriptedAutomation automation ) {
		return AutomationBatch.handleBatch(automation, null, AutomationBatch.batchSizeOf(automation), "scripted", () -> false, null);
	}

	@Test
	void itemsAreHandledInOrderAndTheLastNonEmptyReferenceWins ( ) {
		EventReference first = reference(1);
		ScriptedAutomation automation = automation(List.of("a", "b", "c"),
				item -> switch ( item ) {
					case "a" -> Optional.of(first);
					case "b" -> Optional.empty();       // handled, no event: must not clear the reference
					default -> null;                    // also allowed, same meaning
				},
				(item, cause) -> AutomationFailureAction.RETRY_ITEM);

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(3, outcome.streamed());
		assertEquals(3, outcome.handled());
		assertEquals(0, outcome.failed());
		assertEquals(first, outcome.lastProducedEvent(), "an empty or null return must not overwrite the last produced reference");
		assertFalse(outcome.stopAutomation());
		assertNull(outcome.lastFailure());
		assertFalse(outcome.gotNowhere());
		assertEquals(List.of("pulled:a", "handled:a", "pulled:b", "handled:b", "pulled:c", "handled:c"), interactions,
				"the next item is only pulled once the current one has been handled");
	}

	@Test
	void retryItemAbandonsTheRestOfTheBatch ( ) {
		ScriptedAutomation automation = automation(List.of("a", "poison", "c"),
				item -> {
					if ( item.equals("poison") ) {
						throw new IllegalStateException("the dependency is down");
					}
					return Optional.of(reference(1));
				},
				(item, cause) -> AutomationFailureAction.RETRY_ITEM);

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(2, outcome.streamed());
		assertEquals(1, outcome.handled());
		assertEquals(1, outcome.failed());
		assertFalse(outcome.stopAutomation());
		assertEquals("the dependency is down", outcome.lastFailure().getMessage());
		assertFalse(interactions.contains("handled:c"), "the items behind a RETRY_ITEM failure wait for it");
	}

	@Test
	void continueAndRetryLaterLetsTheItemsBehindProceed ( ) {
		ScriptedAutomation automation = automation(List.of("a", "poison", "c"),
				item -> {
					if ( item.equals("poison") ) {
						throw new IllegalStateException("this one item is poison");
					}
					return Optional.of(reference(1));
				},
				(item, cause) -> AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER);

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(3, outcome.streamed());
		assertEquals(2, outcome.handled());
		assertEquals(1, outcome.failed());
		assertTrue(interactions.contains("handled:c"), "CONTINUE_AND_RETRY_ITEM_LATER lets the items behind a failure proceed");
	}

	@Test
	void stopAutomationAbandonsTheBatchAndAsksTheCallerToStop ( ) {
		ScriptedAutomation automation = automation(List.of("a", "fatal", "c"),
				item -> {
					if ( item.equals("fatal") ) {
						throw new IllegalStateException("a human has to look at this");
					}
					return Optional.empty();
				},
				(item, cause) -> AutomationFailureAction.STOP_AUTOMATION);

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertTrue(outcome.stopAutomation());
		assertEquals("a human has to look at this", outcome.lastFailure().getMessage());
		assertFalse(interactions.contains("handled:c"), "a stopping batch does not touch the items behind the failure");
	}

	@Test
	void aThrowingFailureHandlerIsContainedAsRetryItem ( ) {
		ScriptedAutomation automation = automation(List.of("a", "b"),
				item -> { throw new IllegalStateException("failure on " + item); },
				(item, cause) -> { throw new IllegalStateException("the failure handler is broken too"); });

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(1, outcome.streamed(), "a throwing onFailure means RETRY_ITEM, which abandons the batch");
		assertEquals(1, outcome.failed());
		assertEquals("failure on a", outcome.lastFailure().getMessage(), "the item's own failure is reported, not the handler's");
	}

	@Test
	void aNullFailureActionIsContainedAsRetryItem ( ) {
		ScriptedAutomation automation = automation(List.of("a", "b"),
				item -> { throw new IllegalStateException("failure on " + item); },
				(item, cause) -> null);

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(1, outcome.streamed(), "a null failure action means RETRY_ITEM, which abandons the batch");
		assertEquals(1, outcome.failed());
	}

	@Test
	void abandonIsHonouredAtTheItemBoundary ( ) {
		ScriptedAutomation automation = automation(List.of("a", "b", "c"),
				item -> Optional.empty(),
				(item, cause) -> AutomationFailureAction.RETRY_ITEM);

		// abandon as soon as one item has been handled: b and c stay untouched
		AutomationBatch.Outcome outcome = AutomationBatch.handleBatch(automation, null, AutomationBatch.batchSizeOf(automation), "scripted",
				() -> interactions.contains("handled:a"), null);

		assertEquals(1, outcome.streamed());
		assertEquals(1, outcome.handled());
		assertFalse(interactions.contains("handled:b"), "an abandoned batch never hands out the next item");
	}

	@Test
	void everyItemFailureIsReportedToTheCaller ( ) {
		List<Throwable> reported = new ArrayList<>();
		ScriptedAutomation automation = automation(List.of("a", "b", "c"),
				item -> { throw new IllegalStateException("failure on " + item); },
				(item, cause) -> AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER);

		AutomationBatch.Outcome outcome = AutomationBatch.handleBatch(automation, null, AutomationBatch.batchSizeOf(automation), "scripted",
				() -> false, reported::add);

		assertEquals(3, outcome.failed());
		assertEquals(3, reported.size(), "the caller hears about every failure, not only the last");
		assertTrue(outcome.gotNowhere(), "a batch that failed and handled nothing got nowhere");
	}

	@Test
	void aBatchWithFailuresAndProgressDidNotGetNowhere ( ) {
		assertFalse(new AutomationBatch.Outcome(2, 1, 1, null, false, new IllegalStateException()).gotNowhere());
		assertFalse(new AutomationBatch.Outcome(0, 0, 0, null, false, null).gotNowhere(), "an empty batch found nothing to do, which is not a failure");
		assertTrue(new AutomationBatch.Outcome(1, 0, 1, null, false, new IllegalStateException()).gotNowhere());
	}

	@Test
	void theBatchSizeIsHandedToTheTodoList ( ) {
		ScriptedAutomation automation = automation(List.of("a", "b", "c"), item -> Optional.empty(), (item, cause) -> AutomationFailureAction.RETRY_ITEM);
		automation.batchSize = 2;

		AutomationBatch.Outcome outcome = runBatch(automation);

		assertEquals(Limit.to(2), automation.todoList.lastRequestedLimit);
		assertEquals(2, outcome.streamed());
	}

	@Test
	void anInvalidBatchSizeIsRejectedNamingTheAutomation ( ) {
		ScriptedAutomation automation = automation(List.of(), item -> Optional.empty(), (item, cause) -> AutomationFailureAction.RETRY_ITEM);
		automation.batchSize = 0;

		IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class, () -> AutomationBatch.batchSizeOf(automation));
		assertTrue(rejection.getMessage().contains("ScriptedAutomation"), "the rejection names the automation: " + rejection.getMessage());
	}

}
