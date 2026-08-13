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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailedOnOptimisticLocking;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
import org.sliceworkz.eventmodeling.commands.RetryPolicy;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Pins {@code executeWithRetry} down: only an {@link OptimisticLockingException} is retried, and a
 * retry is a full re-execution — a fresh command context, so the decision models are re-projected
 * and the command re-decides against the facts that made the previous attempt conflict. When the
 * {@link RetryPolicy}'s attempts are exhausted, the last conflict is rethrown unchanged with the
 * earlier ones attached as suppressed exceptions. Everything else — a {@link BusinessException}
 * raised by the re-decide, an {@link IllegalStateException}, any non-conflict failure — propagates
 * from the attempt that raised it. Each attempt is individually observable: one
 * {@link CommandFailedOnOptimisticLocking} per conflict, exactly as plain {@code execute()} emits.
 * <p>
 * Plain {@code @Test}s, since this is framework behaviour rather than storage behaviour.
 */
public class ExecuteWithRetryTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ExecuteWithRetryBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
	private EventStream<MockDomainEvent> domainStream;

	private Mock buildDomain() {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		Mock domain = buildBoundedContext(builder);
		domainStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("domain"), MockDomainEvent.class);
		return domain;
	}

	private long conflictEvents() {
		return received.stream().filter(e -> e instanceof CommandFailedOnOptimisticLocking).count();
	}

	private long stored(Class<? extends MockDomainEvent> type) {
		return domainStream.query(EventQuery.forEvents(EventTypesFilter.of(type), Tags.none())).count();
	}

	// ── commands & decision models ──────────────────────────────────────────

	static class CountingDecisionModel implements DecisionModel<MockDomainEvent> {
		int count;

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			count++;
		}
	}

	/**
	 * Pins its optimistic-lock boundary by reading a decision model, then — for the first
	 * {@code conflictsToInject} attempts — runs {@code injectConflict} (which appends another
	 * matching event after that boundary) before raising its own event, so the framework's append
	 * fails its optimistic-locking check exactly that many times and succeeds on the next attempt.
	 * Holds only immutable inputs plus counters; the decision model is built inside
	 * {@code execute()}, which is what makes re-execution safe.
	 */
	static class RetryableCommand implements Command<MockDomainEvent> {
		final AtomicInteger attempts = new AtomicInteger();
		private final AtomicInteger conflictsToInject;
		private final Runnable injectConflict;

		RetryableCommand(int conflictsToInject, Runnable injectConflict) {
			this.conflictsToInject = new AtomicInteger(conflictsToInject);
			this.injectConflict = injectConflict;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			attempts.incrementAndGet();
			var result = context.decisionModels(new CountingDecisionModel());
			if (conflictsToInject.getAndDecrement() > 0) {
				injectConflict.run();
			}
			result.raiseEvent(new SecondDomainEvent("raised"), Tags.none());
		}
	}

	/**
	 * The uniqueness shape: the rule passes on the first attempt, a concurrent writer takes the
	 * name, and the re-decide of the retry is what turns the conflict into the business rejection.
	 */
	static class UniqueNameCommand implements Command<MockDomainEvent> {
		final AtomicInteger attempts = new AtomicInteger();
		private final AtomicInteger conflictsToInject;
		private final Runnable injectConflict;

		UniqueNameCommand(int conflictsToInject, Runnable injectConflict) {
			this.conflictsToInject = new AtomicInteger(conflictsToInject);
			this.injectConflict = injectConflict;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			attempts.incrementAndGet();
			var model = new CountingDecisionModel();
			var result = context.decisionModels(model);
			BusinessException.when(model.count > 0, "name already taken");
			if (conflictsToInject.getAndDecrement() > 0) {
				injectConflict.run();
			}
			result.raiseEvent(new SecondDomainEvent("raised"), Tags.none());
		}
	}

	static class FailingCommand implements Command<MockDomainEvent> {
		final AtomicInteger attempts = new AtomicInteger();

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			attempts.incrementAndGet();
			context.noDecisionModels();
			throw new IllegalStateException("boom");
		}
	}

	static class RetryableCommandWithResult implements CommandWithResult<MockDomainEvent, String> {
		final AtomicInteger attempts = new AtomicInteger();
		private final AtomicInteger conflictsToInject;
		private final Runnable injectConflict;

		RetryableCommandWithResult(int conflictsToInject, Runnable injectConflict) {
			this.conflictsToInject = new AtomicInteger(conflictsToInject);
			this.injectConflict = injectConflict;
		}

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			attempts.incrementAndGet();
			var result = context.decisionModels(new CountingDecisionModel());
			if (conflictsToInject.getAndDecrement() > 0) {
				injectConflict.run();
			}
			result.raiseEvent(new SecondDomainEvent("raised"), Tags.none());
			return "done";
		}
	}

	static class UnkeyedOutboundCommand implements OutboundCommand<MockDomainEvent, MockOutboundEvent> {
		final AtomicInteger attempts = new AtomicInteger();

		@Override
		public void execute(OutboundCommandContext<MockDomainEvent, MockOutboundEvent> context) {
			attempts.incrementAndGet();
			context.noDecisionModels().raiseEvent(new SomeOutboundEvent("v1"), Tags.none());
		}
	}

	// ── tests ───────────────────────────────────────────────────────────────

	@Test
	void aConflictedCommandIsReExecutedAndSucceeds() {
		Mock domain = buildDomain();
		RetryableCommand command = new RetryableCommand(1, () -> domain.event(new FirstDomainEvent("conflict")));

		Optional<EventReference> reference = domain.executeWithRetry(command, RetryPolicy.of(3));

		assertTrue(reference.isPresent(), "the retried command should have appended its event");
		assertEquals(2, command.attempts.get(), "one conflicted attempt plus one successful re-execution");
		assertEquals(1, conflictEvents(), "each conflicted attempt emits its own event");
		assertEquals(1, received.stream().filter(e -> e instanceof CommandExecuted).count(),
				"the successful attempt is reported exactly once");
		assertEquals(1, stored(SecondDomainEvent.class), "the command's event should be stored exactly once");
	}

	@Test
	void exhaustedAttemptsRethrowTheLastConflictWithEarlierOnesSuppressed() {
		Mock domain = buildDomain();
		RetryableCommand command = new RetryableCommand(100, () -> domain.event(new FirstDomainEvent("conflict")));

		OptimisticLockingException thrown = assertThrows(OptimisticLockingException.class,
				() -> domain.executeWithRetry(command, RetryPolicy.of(3)));

		assertEquals(3, command.attempts.get(), "the policy bounds the attempts");
		assertEquals(2, thrown.getSuppressed().length, "the earlier attempts' conflicts travel as suppressed");
		assertEquals(3, conflictEvents(), "every attempt is individually observable");
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandExecuted),
				"no CommandExecuted for a command that never succeeded, got: " + received);
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandFailed),
				"a conflict is never reported as a generic CommandFailed, got: " + received);
		assertEquals(0, stored(SecondDomainEvent.class), "nothing should have been stored");
	}

	@Test
	void theBareOverloadUsesTheDefaultPolicyOfThreeAttempts() {
		Mock domain = buildDomain();
		RetryableCommand command = new RetryableCommand(100, () -> domain.event(new FirstDomainEvent("conflict")));

		assertThrows(OptimisticLockingException.class, () -> domain.executeWithRetry(command));

		assertEquals(RetryPolicy.DEFAULT.maxAttempts(), command.attempts.get());
	}

	@Test
	void aBusinessRejectionByTheReDecidePropagatesAsTheOutcome() {
		Mock domain = buildDomain();
		// first attempt: boundary empty, rule passes, concurrent writer appends -> conflict;
		// retry re-projects the model, sees the writer's event, and the rule says no
		UniqueNameCommand command = new UniqueNameCommand(1, () -> domain.event(new FirstDomainEvent("taken")));

		BusinessException rejection = assertThrows(BusinessException.class,
				() -> domain.executeWithRetry(command, RetryPolicy.of(5)));

		assertEquals("name already taken", rejection.getMessage());
		assertEquals(2, command.attempts.get(), "the rejection ends the retrying, well before the policy would");
		assertEquals(0, stored(SecondDomainEvent.class), "the rejected command should have stored nothing");
	}

	@Test
	void aNonConflictFailureIsNeverRetried() {
		Mock domain = buildDomain();
		FailingCommand command = new FailingCommand();

		assertThrows(IllegalStateException.class, () -> domain.executeWithRetry(command, RetryPolicy.of(5)));

		assertEquals(1, command.attempts.get(), "only a DCB conflict is worth re-executing");
	}

	@Test
	void anIdempotencyKeySurvivesTheRetryAndStillDeduplicates() {
		Mock domain = buildDomain();
		RetryableCommand command = new RetryableCommand(1, () -> domain.event(new FirstDomainEvent("conflict")));

		Optional<EventReference> reference = domain.executeWithRetry(command, "key-1", RetryPolicy.of(3));

		assertTrue(reference.isPresent(), "the conflicted attempt stored nothing, so the key was still unconsumed");
		assertEquals(2, command.attempts.get());

		// the key was consumed by the successful attempt, so a repeat under the same key stores nothing
		Optional<EventReference> repeat = domain.execute(new RetryableCommand(0, () -> { }), "key-1");
		assertTrue(repeat.isEmpty(), "a repeat under the same key de-duplicates");
		assertEquals(1, stored(SecondDomainEvent.class));
	}

	@Test
	void aCommandWithResultReturnsItsResponseAfterARetriedConflict() {
		Mock domain = buildDomain();
		RetryableCommandWithResult command = new RetryableCommandWithResult(1,
				() -> domain.event(new FirstDomainEvent("conflict")));

		CommandExecutionResult<String> result = domain.executeWithRetry(command, RetryPolicy.of(3));

		assertEquals("done", result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(2, command.attempts.get());
	}

	@Test
	void anOutboundCommandsGuardFailureIsNeverRetried() {
		Mock domain = buildDomain();
		UnkeyedOutboundCommand command = new UnkeyedOutboundCommand();

		// the missing-key guard is an IllegalStateException, not a conflict, so it propagates at once
		assertThrows(IllegalStateException.class, () -> domain.executeWithRetry(command, RetryPolicy.of(5)));
		assertEquals(1, command.attempts.get());
	}

	@Test
	void anOutboundCommandExecutesThroughTheRetryTwin() {
		Mock domain = buildDomain();
		UnkeyedOutboundCommand command = new UnkeyedOutboundCommand();

		Optional<EventReference> reference = domain.executeWithRetry(command, "external/1", RetryPolicy.of(3));

		assertTrue(reference.isPresent());
		assertEquals(1, command.attempts.get());
	}

}
