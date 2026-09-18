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
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandRejected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.DecisionModelProjected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Tests that a command execution that does not succeed is reported through a dedicated
 * bounded-context event and the exception is rethrown to the caller: a
 * {@link CommandFailedOnOptimisticLocking} for an optimistic-locking conflict on append, a
 * {@link CommandRejected} for a {@link BusinessException} — the reason alone, no stack trace — and a
 * {@link CommandFailed} for any other exception. In every case no {@link CommandExecuted} is emitted,
 * while the decision-model reads that did happen before the outcome are still reported as
 * {@link DecisionModelProjected} events.
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

	/**
	 * The shape of every business rule in WHERE-VALIDATIONS-GO.md: read a decision model, check,
	 * and say no with a {@link BusinessException}.
	 */
	static class RejectingCommand implements Command<MockDomainEvent> {
		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.decisionModels(new FirstDecisionModel());
			BusinessException.because("insufficient balance");
		}
	}

	static class RejectingCommandWithResult implements CommandWithResult<MockDomainEvent, String> {
		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
			throw new BusinessException("period is closed");
		}
	}

	/**
	 * A rule judged while history is read — the wrong place for one — which the projector wraps
	 * before the module sees it.
	 */
	static class JudgingDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			throw new BusinessException("judged while reading");
		}
	}

	static class CommandOverAJudgingModel implements Command<MockDomainEvent> {
		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.decisionModels(new JudgingDecisionModel());
			result.raiseEvent(new FirstDomainEvent("never raised"), Tags.none());
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

	@Test
	void aBusinessRejectionEmitsCommandRejectedWithTheReasonAndRethrows() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		RejectingCommand command = new RejectingCommand();
		BusinessException thrown = assertThrows(BusinessException.class, () -> domain.execute(command),
				"the business exception should reach the caller unchanged");
		assertEquals("insufficient balance", thrown.getMessage());

		CommandRejected rejected = received.stream()
				.filter(e -> e instanceof CommandRejected)
				.map(e -> (CommandRejected) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandRejected event, got: " + received));

		assertEquals(CONTEXT_NAME, rejected.boundedContext());
		assertEquals(command.commandName(), rejected.command());
		assertEquals("insufficient balance", rejected.reason());
		assertNotNull(rejected.metrics());

		assertTrue(received.stream().anyMatch(e -> e instanceof DecisionModelProjected),
				"the decision-model read the rule was decided on should still be reported, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandFailed),
				"a business rejection is not a failure and must not be reported with a stack trace, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandExecuted),
				"no CommandExecuted should be emitted for a rejected command, got: " + received);
	}

	@Test
	void aCommandWithResultIsRejectedTheSameWay() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		RejectingCommandWithResult command = new RejectingCommandWithResult();
		assertThrows(BusinessException.class, () -> domain.execute(command));

		CommandRejected rejected = received.stream()
				.filter(e -> e instanceof CommandRejected)
				.map(e -> (CommandRejected) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandRejected event, got: " + received));
		assertEquals(command.commandName(), rejected.command());
		assertEquals("period is closed", rejected.reason());
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandFailed),
				"a business rejection is not a failure, got: " + received);
	}

	/**
	 * The point of the separate event: a listener persisting the kernel events stores a rejection as
	 * its reason and nothing else, where a {@link CommandFailed} stores a rendered stack trace.
	 */
	@Test
	void aRejectionIsPersistedWithoutAStackTrace() {
		EventStore eventStore = EventStore.on(eventStorage()).build();
		EventStreamId kernelStreamId = EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel");
		EventStream<BoundedContextEvent> kernelStream = eventStore.getEventStream(kernelStreamId, BoundedContextEvent.class);
		Mock domain = buildDomain(new StreamAppendingBoundedContextListener(kernelStream));

		assertThrows(BusinessException.class, () -> domain.execute(new RejectingCommand()));

		EventQuery outcomes = EventQuery.forEvents(EventTypesFilter.of(CommandRejected.class, CommandFailed.class), Tags.none());
		List<Event<BoundedContextEvent>> persisted = kernelStream.query(outcomes);
		assertEquals(1, persisted.size(), "expected exactly the rejection to be persisted, got: " + persisted);
		CommandRejected rejected = (CommandRejected) persisted.get(0).data();
		assertEquals("insufficient balance", rejected.reason());

		// the stored document is the reason and nothing rendered from the exception: read it as stored
		String document = eventStore.getRawEventStream(kernelStreamId).query(outcomes).get(0).data();
		assertTrue(document.contains("insufficient balance"), "expected the reason in the stored document: " + document);
		assertTrue(!document.contains("stackTrace") && !document.contains(RejectingCommand.class.getName()),
				"a persisted rejection must not carry a stack trace, got: " + document);
	}

	/**
	 * A {@link BusinessException} is recognised as the exception the command itself threw. One thrown
	 * from inside a decision model arrives wrapped by the projector and is reported as a failure — a
	 * rule judged on the read path is a rule in the wrong place, and the stack trace is what says so.
	 */
	@Test
	void aRuleThrownFromInsideADecisionModelIsReportedAsAFailure() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));
		domain.event(new FirstDomainEvent("seed"));

		assertThrows(RuntimeException.class, () -> domain.execute(new CommandOverAJudgingModel()));

		assertTrue(received.stream().anyMatch(e -> e instanceof CommandFailed),
				"a rule thrown while projecting is a failure, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandRejected),
				"a wrapped business exception is not the command's own rejection, got: " + received);
	}

}
