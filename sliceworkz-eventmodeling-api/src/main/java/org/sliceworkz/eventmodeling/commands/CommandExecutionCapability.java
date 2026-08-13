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
package org.sliceworkz.eventmodeling.commands;

import java.util.Optional;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public interface CommandExecutionCapability<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command );

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing );

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey );

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing );

	Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command );

	Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tracing );

	Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey );

	Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey, Tracing tracing );

	<RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command );

	<RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing );

	<RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey );

	<RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing );

	// ── executeWithRetry: bounded re-execution on optimistic-locking conflicts ──────────────────
	//
	// An OptimisticLockingException is the routine DCB outcome under contention -- new relevant
	// facts were appended after the command made its decision -- and the documented answer is to
	// re-execute: each execute() builds a fresh command context, so the decision models are
	// re-projected and the command re-decides against the new facts. These helpers are that loop,
	// written once, so a caller cannot write it wrong (the classic mistake being a retry that
	// reuses the stale decision instead of re-reading).
	//
	// What they promise, and deliberately do not:
	// - ONLY OptimisticLockingException is retried. Everything else -- BusinessException,
	//   IllegalArgumentException, IllegalStateException, EventStorageException -- propagates from
	//   the attempt that raised it: those failures are identical on re-execution or need a
	//   different response entirely.
	// - A retry that ends in a BusinessException is the helper working, not failing: under the
	//   empty-boundary uniqueness pattern the conflict means someone else took the name first, and
	//   the re-decide is exactly what turns that into the business rejection.
	// - When the attempts are exhausted, the LAST OptimisticLockingException is rethrown
	//   unchanged -- existing catch-by-name code keeps working -- with the earlier attempts'
	//   conflicts attached as suppressed exceptions.
	// - Attempts are bounded and there is no delay between them; see RetryPolicy for why.
	// - The SAME command instance is re-executed. A command holding mutable state across execute()
	//   calls, or building its decision models in its constructor, is not safely re-executable --
	//   idiomatic commands hold only immutable inputs and construct their models inside execute().
	//   Nothing enforces this.
	// - An idempotency key is carried unchanged into every attempt: a conflicted append stored
	//   nothing, so the key is unconsumed. If a concurrent writer already appended under the same
	//   key, the retried append de-duplicates and returns Optional.empty() -- for an at-least-once
	//   caller that is success, the work was already done.
	// - Every attempt is observable on its own: each conflict emits CommandFailedOnOptimisticLocking
	//   and increments the command meters exactly as a plain execute() does, so a consumer counts
	//   attempts rather than needing a dedicated exhaustion event.
	//
	// Inside an automation's handle() this is the "failure worth retrying in milliseconds" retry
	// that the framework deliberately does not do at the item level -- use it there, and leave the
	// batch-level policy to Automation.onFailure.

	private <RESULT> RESULT retryingOnConflict ( RetryPolicy policy, Supplier<RESULT> attempt ) {
		OptimisticLockingException[] earlier = new OptimisticLockingException[policy.maxAttempts() - 1];
		for ( int attemptNo = 1; ; attemptNo++ ) {
			try {
				return attempt.get();
			} catch ( OptimisticLockingException conflict ) {	// only a DCB conflict is ever retried
				if ( attemptNo == policy.maxAttempts() ) {
					for ( int i = 0; i < attemptNo - 1; i++ ) {
						conflict.addSuppressed(earlier[i]);
					}
					throw conflict;								// the last conflict, unchanged
				}
				earlier[attemptNo - 1] = conflict;				// no delay: re-executing re-reads the new facts
			}
		}
	}

	/**
	 * Executes the command with {@link RetryPolicy#DEFAULT}, re-executing it on an
	 * {@link OptimisticLockingException}. See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( Command<DOMAIN_EVENT_TYPE> command ) {
		return executeWithRetry(command, RetryPolicy.DEFAULT);
	}

	/**
	 * Executes the command, re-executing it on an {@link OptimisticLockingException} until it
	 * succeeds, fails with anything else, or the policy's attempts are exhausted — in which case
	 * the last conflict is rethrown unchanged, earlier ones attached as suppressed exceptions.
	 * Re-execution re-projects the command's decision models, so each attempt decides against the
	 * facts that caused the previous one to conflict. Only safe for a command that holds no
	 * mutable state across {@code execute()} calls.
	 */
	default Optional<EventReference> executeWithRetry ( Command<DOMAIN_EVENT_TYPE> command, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command));
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, tracing));
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}. The idempotency key is carried
	 * unchanged into every attempt; a retried append that finds the key already used
	 * de-duplicates and returns {@code Optional.empty()}.
	 */
	default Optional<EventReference> executeWithRetry ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey));
	}

	/**
	 * See {@link #executeWithRetry(Command, String, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey, tracing));
	}

	/**
	 * Executes the outbound command with {@link RetryPolicy#DEFAULT}, re-executing it on an
	 * {@link OptimisticLockingException}. See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command ) {
		return executeWithRetry(command, RetryPolicy.DEFAULT);
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command));
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, tracing));
	}

	/**
	 * See {@link #executeWithRetry(Command, String, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey));
	}

	/**
	 * See {@link #executeWithRetry(Command, String, RetryPolicy)}.
	 */
	default Optional<EventReference> executeWithRetry ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey, tracing));
	}

	/**
	 * Executes the command with {@link RetryPolicy#DEFAULT}, re-executing it on an
	 * {@link OptimisticLockingException}. See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeWithRetry ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command ) {
		return executeWithRetry(command, RetryPolicy.DEFAULT);
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeWithRetry ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command));
	}

	/**
	 * See {@link #executeWithRetry(Command, RetryPolicy)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeWithRetry ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, tracing));
	}

	/**
	 * See {@link #executeWithRetry(Command, String, RetryPolicy)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeWithRetry ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey));
	}

	/**
	 * See {@link #executeWithRetry(Command, String, RetryPolicy)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeWithRetry ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing, RetryPolicy retryPolicy ) {
		return retryingOnConflict(retryPolicy, () -> execute(command, idempotencyKey, tracing));
	}

}
