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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailedOnOptimisticLocking;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.commands.RetryPolicy;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * The retry an automation is meant to do inside {@code handle()} — a DCB conflict cleared in
 * milliseconds by re-executing the command — through {@code context.executeWithRetry(...)}: the
 * item completes within one {@code handle} call, so {@code onFailure} is never consulted and the
 * batch is never abandoned. This is the complement of the framework's deliberate choice not to
 * retry at the item level (see {@code AutomationProcessor}): the item-level machinery answers
 * failures that outlive a batch, the in-handle retry answers the one that does not.
 * <p>
 * A plain {@code @Test}, since this is framework behaviour rather than storage behaviour.
 */
public class AutomationExecuteWithRetryTest extends AbstractMockDomainTest {

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
	private final List<String> handled = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger commandAttempts = new AtomicInteger();
	private final AtomicInteger failuresSeen = new AtomicInteger();

	@Test
	void aConflictInsideHandleIsRetriedWithinTheSameHandleCall() {
		DroppingTodoList todoList = new DroppingTodoList();
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new RetryingAutomation(todoList));
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("item-0"));

		await().atMost(Duration.ofSeconds(40)).untilAsserted(
				() -> assertEquals(List.of("item-0"), handled, "the item should complete despite the conflict"));

		assertEquals(2, commandAttempts.get(), "one conflicted attempt plus one successful re-execution");
		assertEquals(0, failuresSeen.get(), "the conflict is cleared inside handle(), so onFailure is never consulted");
		assertEquals(1, received.stream().filter(e -> e instanceof CommandFailedOnOptimisticLocking).count(),
				"the conflicted attempt is still individually observable");
	}

	// ── the pieces ──────────────────────────────────────────────────────────

	static class ThirdEventDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
		}
	}

	/**
	 * Completes a todo item by raising the event that makes the todo list drop it, conflicting once
	 * on the way: the first attempt appends a matching {@link ThirdDomainEvent} after pinning its
	 * boundary, so the framework's append fails its optimistic-locking check exactly once.
	 */
	class CompleteItemCommand implements Command<MockDomainEvent> {
		private final String item;
		private final AtomicInteger conflictsToInject = new AtomicInteger(1);

		CompleteItemCommand(String item) {
			this.item = item;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			commandAttempts.incrementAndGet();
			var result = context.decisionModels(new ThirdEventDecisionModel());
			if (conflictsToInject.getAndDecrement() > 0) {
				boundedContext.event(new ThirdDomainEvent("conflict"));
			}
			result.raiseEvent(new SecondDomainEvent(item), Tags.none());
		}
	}

	/** Named rather than anonymous: an automation's bookmark is keyed on its simple name. */
	class RetryingAutomation implements Automation<String, MockDomainEvent, MockOutboundEvent> {

		private final DroppingTodoList todoList;

		RetryingAutomation(DroppingTodoList todoList) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent, String> getTodoList() {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle(String todoItem, AutomationContext<MockDomainEvent, MockOutboundEvent> context) {
			Optional<EventReference> reference = context.executeWithRetry(new CompleteItemCommand(todoItem), RetryPolicy.of(3));
			handled.add(todoItem);
			return reference;
		}

		@Override
		public AutomationFailureAction onFailure(String todoItem, Throwable cause, AutomationContext<MockDomainEvent, MockOutboundEvent> context) {
			failuresSeen.incrementAndGet();
			return AutomationFailureAction.RETRY_ITEM;
		}
	}

	/** Items appear with {@link FirstDomainEvent} and are dropped by the matching {@link SecondDomainEvent}. */
	static class DroppingTodoList implements TodoListReadModel<MockDomainEvent, String> {

		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		@Override
		public String readmodelName() {
			return "todo-retrying";
		}

		@Override
		public ReadModelStorage storage() {
			return ReadModelStorage.EPHEMERAL;
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			if (event.data() instanceof FirstDomainEvent f) {
				items.add(f.value());
			}
			if (event.data() instanceof SecondDomainEvent s) {
				items.remove(s.value());
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems(Limit limit) {
			List<String> snapshot = List.copyOf(items);
			int max = (int) Math.min(snapshot.size(), limit.value());
			return IntStream.range(0, max).mapToObj(snapshot::get);
		}

		@Override
		public Optional<EventReference> lastEventReference() {
			return Optional.ofNullable(lastEventReference);
		}
	}

}
