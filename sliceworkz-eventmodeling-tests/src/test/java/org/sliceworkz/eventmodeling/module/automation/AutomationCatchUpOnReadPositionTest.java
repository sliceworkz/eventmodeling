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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.Handled;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.TestAutomation;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.TodoList;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * An automation takes a fresh look at its todo list only once the todo list's projector has caught up
 * with the last event the automation produced. When that event is of a type the todo list does not read,
 * the projector never handles it, so the last event it handled never gets there — and the automation used
 * to sit out its poll interval after every batch. What it waits for is how far the projector has
 * <em>read</em> the stream: its bookmark's read position.
 */
public class AutomationCatchUpOnReadPositionTest extends AbstractMockDomainTest {

	@Test
	void anEventTheTodoListDoesNotReadIsNotWaitedOut ( ) {
		// reads FirstDomainEvent (an item) and SecondDomainEvent (done); never ThirdDomainEvent
		TodoList todoList = new TodoList("todo-read-position");
		Handled handled = new Handled();

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			handled.add(item);
			context.event(new SecondDomainEvent(item));
			// the last event produced, and one the todo list does not read
			Optional<EventReference> last = context.event(new ThirdDomainEvent("audit of " + item));
			return last;
		});
		// one item per batch, so every item waits for the todo list to have caught up with the one before
		automation.batchSize = 1;

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(automation);
		buildBoundedContext(builder);

		boundedContext.event(new FirstDomainEvent("item-0"));
		boundedContext.event(new FirstDomainEvent("item-1"));

		// waiting on the last event handled, the second item comes only after the 10s poll interval
		await().atMost(Duration.ofSeconds(6)).untilAsserted(
			() -> assertEquals(List.of("item-0", "item-1"), handled.items(),
				"the second item should not wait out the poll interval for an event the todo list does not read"));
	}

}
