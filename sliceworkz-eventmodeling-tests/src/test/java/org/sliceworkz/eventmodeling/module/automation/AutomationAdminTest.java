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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.AutomationStartReason;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.Handled;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.TestAutomation;
import org.sliceworkz.eventmodeling.module.automation.AutomationFailureRecoveryTest.TodoList;

/**
 * An automation only ever stops because it asked to, and nothing restarts it on its own — so an operator
 * has to be able to see that it is down, see why, and put it back. Before this the only signals were an
 * ERROR line and a counter, and the only way back was starting the whole bounded context.
 */
public class AutomationAdminTest extends AbstractMockDomainTest {

	private final List<BoundedContextEvent> events = Collections.synchronizedList(new ArrayList<>());

	@Test
	void aRunningAutomationIsReportedFromStartup ( ) {
		TodoList todoList = new TodoList("todo-admin-running");
		start(todoList, new TestAutomation(todoList, (item, context) -> Optional.empty()));

		List<AutomationStatus> automations = boundedContext.automations();

		assertEquals(1, automations.size());
		AutomationStatus status = automations.get(0);
		assertTrue(status.running());
		assertEquals("TestAutomation", status.automationClass());
		assertEquals(0, status.itemsFailed());
		assertTrue(status.stoppedBy() == null, "a running automation has nothing that stopped it");

		// visible without having processed anything: an automation with an empty todo list would otherwise
		// be indistinguishable from one that is not deployed
		assertTrue(startedEvents().stream().anyMatch(e -> e.reason() == AutomationStartReason.BOUNDED_CONTEXT_START),
			"starting the context should announce its automations");
	}

	@Test
	void aStoppedAutomationReportsWhatStoppedItAndCanBeRestarted ( ) {
		TodoList todoList = new TodoList("todo-admin-restart");
		Handled handled = new Handled();
		AtomicBoolean broken = new AtomicBoolean(true);

		TestAutomation automation = new TestAutomation(todoList, (item, context) -> {
			if ( broken.get() ) {
				throw new IllegalStateException("the thing an operator has to go and fix");
			}
			handled.add(item);
			return context.event(new MockDomainEvent.SecondDomainEvent(item));
		});
		automation.failureAction = AutomationFailureAction.STOP_AUTOMATION;
		start(todoList, automation);

		boundedContext.event(new FirstDomainEvent("item-0"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertFalse(boundedContext.automations().get(0).running(), "the automation should have stopped itself"));

		AutomationStatus stopped = boundedContext.automations().get(0);
		assertNotNull(stopped.stoppedBy(), "an operator needs to see why it stopped");
		assertEquals(IllegalStateException.class.getName(), stopped.stoppedBy().type());
		assertEquals("the thing an operator has to go and fix", stopped.stoppedBy().message());
		assertTrue(stopped.itemsFailed() >= 1);

		// the announcement follows the state change rather than preceding it, so it is awaited
		await().atMost(Duration.ofSeconds(5)).untilAsserted(
			() -> assertTrue(events.stream().anyMatch(e -> e instanceof BoundedContextEvent.AutomationStopped),
				"a stopped automation should be announced, not only logged"));

		// the item that stopped it is still outstanding, so a restart handles it again -- which is why an
		// operator fixes the cause first
		assertTrue(todoList.items().contains("item-0"), "the failing item stays on the todo list");
		broken.set(false);

		assertTrue(boundedContext.restartAutomation(stopped.automation()), "restarting a stopped automation reports that it did");

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
			() -> assertEquals(List.of("item-0"), handled.items(), "the restarted automation should pick its todo list up again"));

		assertTrue(boundedContext.automations().get(0).running());
		assertTrue(startedEvents().stream().anyMatch(e -> e.reason() == AutomationStartReason.RESTART),
			"a restart should be announced as such, not as a context start");
		assertTrue(boundedContext.automations().get(0).stoppedBy() == null, "a running automation has nothing that stopped it");
		assertNotNull(boundedContext.automations().get(0).lastFailure(), "but what went wrong is still on record");
	}

	@Test
	void restartingARunningAutomationDoesNothing ( ) {
		TodoList todoList = new TodoList("todo-admin-noop");
		start(todoList, new TestAutomation(todoList, (item, context) -> Optional.empty()));

		String id = boundedContext.automations().get(0).automation();

		assertFalse(boundedContext.restartAutomation(id), "restarting something that is running reports that it did nothing");
		assertTrue(boundedContext.automations().get(0).running());
	}

	@Test
	void restartingAnUnknownAutomationSaysWhichOnesExist ( ) {
		TodoList todoList = new TodoList("todo-admin-unknown");
		start(todoList, new TestAutomation(todoList, (item, context) -> Optional.empty()));

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
			() -> boundedContext.restartAutomation("NoSuchAutomation"));

		assertTrue(failure.getMessage().contains("NoSuchAutomation"));
		assertTrue(failure.getMessage().contains("TestAutomation"), "the message should name the automations there are");
	}

	private List<BoundedContextEvent.AutomationStarted> startedEvents ( ) {
		return events.stream()
				.filter(BoundedContextEvent.AutomationStarted.class::isInstance)
				.map(BoundedContextEvent.AutomationStarted.class::cast)
				.toList();
	}

	private void start ( TodoList todoList, TestAutomation automation ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> events.add(event.data()));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(automation);
		buildBoundedContext(builder);
	}
}
