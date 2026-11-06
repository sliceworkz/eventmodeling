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

/**
 * How a command is executed: under which idempotency key, and how often it is re-executed when its
 * append conflicts. The command says what is decided; this says how the execution of it goes, the way an
 * {@code EventQuery} says how an {@code EventFilter} is read.
 * <pre>{@code
 * context.execute(command);                                                   // once, no key
 * context.execute(command, Execution.retrying());                             // re-executed on a conflict
 * context.execute(command, Execution.idempotent(requestId));                  // a repeat is swallowed
 * context.execute(command, Execution.idempotent(requestId).withRetry());      // both
 * }</pre>
 *
 * <h2>The idempotency key</h2>
 * A key given here is the caller's: the command's {@link CommandResult} decides what becomes of it
 * ({@code idempotencyKey}, {@code fallbackIdempotencyKey}, ...). It is applied to the events the command
 * raises — a single event keeps it as it is, several get {@code <key>/1}, {@code <key>/2}, … in raise
 * order — and an execution whose every key was stored before is swallowed and answers
 * {@code Optional.empty()}.
 *
 * <h2>Retrying on a conflict</h2>
 * An {@link org.sliceworkz.eventstore.stream.OptimisticLockingException} is the routine DCB outcome under
 * contention: relevant facts were appended after the command decided. The answer is to execute it again,
 * which re-projects its decision models and re-decides against the new facts — and that loop is what
 * {@link #retrying()} asks for, written once in the framework so a caller cannot write it wrong (the
 * classic mistake being a retry that reuses the stale decision instead of re-reading).
 * <ul>
 * <li>Only {@code OptimisticLockingException} is retried. A {@link BusinessException},
 * {@code IllegalArgumentException}, {@code IllegalStateException} or {@code EventStorageException}
 * propagates from the attempt that raised it. A retry that ends in a {@code BusinessException} is the
 * retry working: under the empty-boundary uniqueness pattern the conflict means someone else took the name
 * first, and the re-decide is what turns that into the rejection.</li>
 * <li>When the attempts are exhausted, the last conflict is rethrown unchanged, the earlier ones attached
 * as suppressed exceptions. There is no delay between attempts; see {@link RetryPolicy}.</li>
 * <li>The same command instance is executed again, so it must hold no mutable state across
 * {@code execute()} calls and build its decision models inside {@code execute()}.</li>
 * <li>The idempotency key is carried into every attempt unchanged: a conflicted append stored nothing.</li>
 * <li>Every attempt is an execution of its own, observed and reported as one.</li>
 * </ul>
 * Inside an automation's {@code handle()} this is the retry that the framework deliberately does not do
 * at the item level: use it there, and leave the batch-level policy to {@code Automation.onFailure}.
 * <p>
 * The alternative — an {@code executeWithRetry} beside {@code execute}, each overloaded per key — loses
 * because it is one choice spread over two method names and ten overloads, where a value says it once.
 *
 * @param idempotencyKey the caller's idempotency key, or {@code null} for none
 * @param retryPolicy how many attempts a conflict may cost; {@link #ONCE}'s policy is a single attempt
 */
public record Execution ( String idempotencyKey, RetryPolicy retryPolicy ) {

	private static final RetryPolicy SINGLE_ATTEMPT = RetryPolicy.of(1);

	/**
	 * Once, with no idempotency key: what {@code execute(command)} does.
	 */
	public static final Execution ONCE = new Execution(null, SINGLE_ATTEMPT);

	public Execution {
		if ( idempotencyKey != null && idempotencyKey.isBlank() ) {
			throw new IllegalArgumentException("an idempotency key needs a value; leave it out for none");
		}
		retryPolicy = retryPolicy != null ? retryPolicy : SINGLE_ATTEMPT;
	}

	/**
	 * Re-executed on a conflict, with {@link RetryPolicy#DEFAULT}.
	 */
	public static Execution retrying ( ) {
		return ONCE.withRetry();
	}

	/**
	 * Re-executed on a conflict, as often as the policy allows.
	 */
	public static Execution retrying ( RetryPolicy retryPolicy ) {
		return ONCE.withRetry(retryPolicy);
	}

	/**
	 * Under the given idempotency key, once.
	 */
	public static Execution idempotent ( String idempotencyKey ) {
		return ONCE.withIdempotencyKey(idempotencyKey);
	}

	/**
	 * This execution, re-executed on a conflict with {@link RetryPolicy#DEFAULT}.
	 */
	public Execution withRetry ( ) {
		return withRetry(RetryPolicy.DEFAULT);
	}

	/**
	 * This execution, re-executed on a conflict as often as the policy allows.
	 */
	public Execution withRetry ( RetryPolicy retryPolicy ) {
		if ( retryPolicy == null ) {
			throw new IllegalArgumentException("a retry policy is required");
		}
		return new Execution(idempotencyKey, retryPolicy);
	}

	/**
	 * This execution, under the given idempotency key.
	 */
	public Execution withIdempotencyKey ( String idempotencyKey ) {
		if ( idempotencyKey == null ) {
			throw new IllegalArgumentException("an idempotency key is required");
		}
		return new Execution(idempotencyKey, retryPolicy);
	}

}
