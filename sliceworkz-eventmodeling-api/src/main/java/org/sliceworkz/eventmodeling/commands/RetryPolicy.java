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
 * How often {@code executeWithRetry} attempts a command before giving up and rethrowing the last
 * {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}.
 *
 * <p>
 * {@code maxAttempts} counts the first execution, so {@code RetryPolicy.of(3)} means one attempt
 * plus at most two retries. Attempts are deliberately bounded: a conflict is normally cleared by
 * re-executing — the command re-projects its decision models and re-decides against the new facts —
 * but a conflict that keeps recurring means sustained contention (or, on PostgreSQL, a visibility
 * stall no amount of retrying can clear), and looping on it would only add load.
 * </p>
 *
 * <p>
 * There is deliberately no delay between attempts. A DCB conflict means new relevant facts exist,
 * and re-reading them is the fix — waiting adds latency without improving the odds. Pacing repeated
 * failures over time is an automation concern and lives at
 * {@code Automation.delayBeforeNextBatch(...)}, not here.
 * </p>
 *
 * <p>
 * Prefer {@link #of(int)} or {@link #DEFAULT} over the canonical constructor: a record's canonical
 * constructor cannot grow compatibly, the factories can.
 * </p>
 *
 * @param maxAttempts total number of attempts, including the first execution; at least 1
 */
public record RetryPolicy ( int maxAttempts ) {

	/**
	 * The default policy: three attempts in total (one execution plus at most two retries).
	 */
	public static final RetryPolicy DEFAULT = new RetryPolicy(3);

	public RetryPolicy {
		if ( maxAttempts < 1 ) {
			throw new IllegalArgumentException("maxAttempts must be >= 1 (it counts the first attempt), got " + maxAttempts);
		}
	}

	/**
	 * A policy attempting the command at most {@code maxAttempts} times in total.
	 *
	 * @param maxAttempts total number of attempts, including the first execution; at least 1
	 * @return the policy
	 */
	public static RetryPolicy of ( int maxAttempts ) {
		return new RetryPolicy(maxAttempts);
	}

}
