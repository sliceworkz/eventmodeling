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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailedOnOptimisticLocking;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.DecisionModelProjected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Tests that a failing command execution is reported through a dedicated bounded-context event and
 * the exception is rethrown to the caller: a {@link CommandFailedOnOptimisticLocking} for an
 * optimistic-locking conflict on append, a {@link CommandFailed} for any other exception. In both
 * cases no {@link CommandExecuted} is emitted, while the decision-model reads that did happen before
 * the failure are still reported as {@link DecisionModelProjected} events.
 */
public class CommandFailedTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "CommandFailedBoundedContext";

	private Mock buildDomain(BoundedContextListener listener) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(listener);
		return buildBoundedContext(builder);
	}

	// ── commands & decision models ──────────────────────────────────────────

	static class FailingCommand implements Command<MockDomainEvent> {
		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
			throw new IllegalStateException("boom");
		}
	}

	static class FirstDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
		}
	}

	/**
	 * Pins its optimistic-lock boundary by reading a decision model, then runs {@code injectConflict}
	 * (which appends another matching event after that boundary) before raising its own event. The
	 * framework's append then fails its optimistic-locking check.
	 */
	static class ConflictingCommand implements Command<MockDomainEvent> {
		private final Runnable injectConflict;

		ConflictingCommand(Runnable injectConflict) {
			this.injectConflict = injectConflict;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.decisionModels(new FirstDecisionModel());
			injectConflict.run();
			result.raiseEvent(new FirstDomainEvent("raised"), Tags.none());
		}
	}

	// ── tests ───────────────────────────────────────────────────────────────

	@Test
	void failingCommandEmitsCommandFailedAndRethrows() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		FailingCommand command = new FailingCommand();
		// the bounded-context proxy propagates the real exception to the caller
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> domain.execute(command));
		assertEquals("boom", thrown.getMessage());

		CommandFailed failed = received.stream()
				.filter(e -> e instanceof CommandFailed)
				.map(e -> (CommandFailed) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandFailed event, got: " + received));

		assertEquals(CONTEXT_NAME, failed.boundedContext());
		assertEquals(command.commandName(), failed.command());
		assertNotNull(failed.metrics());
		assertNotNull(failed.failure(), "expected the exception to be captured");
		assertEquals(IllegalStateException.class.getName(), failed.failure().type());
		assertEquals("boom", failed.failure().message());
		assertTrue(failed.failure().stackTrace().contains(FailingCommand.class.getName()),
				"expected the stack trace to mention the failing command, got: " + failed.failure().stackTrace());

		assertTrue(received.stream().noneMatch(e -> e instanceof CommandExecuted),
				"no CommandExecuted should be emitted for a failed command, got: " + received);
	}

	@Test
	void optimisticLockingConflictEmitsDedicatedEventAndRethrows() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		// pre-seed a matching event so the command's decision-model boundary is pinned to a real reference
		domain.event(new FirstDomainEvent("seed"));

		// the command pins its boundary, then a conflicting matching event is appended before its own append
		ConflictingCommand command = new ConflictingCommand(() -> domain.event(new FirstDomainEvent("conflict")));
		// the bounded-context proxy propagates the real exception to the caller
		assertThrows(OptimisticLockingException.class, () -> domain.execute(command),
				"the optimistic-locking exception should reach the caller");

		CommandFailedOnOptimisticLocking conflict = received.stream()
				.filter(e -> e instanceof CommandFailedOnOptimisticLocking)
				.map(e -> (CommandFailedOnOptimisticLocking) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandFailedOnOptimisticLocking event, got: " + received));

		assertEquals(CONTEXT_NAME, conflict.boundedContext());
		assertEquals(command.commandName(), conflict.command());
		assertNotNull(conflict.metrics());
		assertNotNull(conflict.expectedLastEvent(), "expected the pinned boundary reference to be captured");

		assertTrue(received.stream().anyMatch(e -> e instanceof DecisionModelProjected),
				"decision-model reads that happened before the failure should still be reported, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandFailed),
				"an optimistic-locking conflict should not be reported as a generic CommandFailed, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandExecuted),
				"no CommandExecuted should be emitted for a conflicting command, got: " + received);
	}

}
