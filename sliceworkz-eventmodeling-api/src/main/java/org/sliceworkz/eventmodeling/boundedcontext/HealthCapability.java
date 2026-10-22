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
package org.sliceworkz.eventmodeling.boundedcontext;

import java.time.Duration;

import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * How a bounded context is doing on this instance — what a health endpoint asks, filed with the operator's
 * capabilities ({@link OperationsCapabilities}).
 * <p>
 * {@link #health(Duration)} combines the event storage's own report ({@link EventStorage#health(Duration)})
 * with what only the framework can judge, every check of which is described on
 * {@link BoundedContextHealth}. The processor checks — one per kind deployed here — follow one rule, and
 * judge only a context that is {@link BoundedContextState#RUNNING}, since before that every processor is
 * parked on purpose:
 * <ul>
 *   <li><b>retired on a failure</b> — a projector or automation that stopped itself and waits for an
 *       operator ({@code stoppedBy} on its status) — is {@link org.sliceworkz.eventstore.observability.HealthStatus#DEGRADED DEGRADED}:
 *       the context serves, and that component's work is not being done anywhere this instance can see;</li>
 *   <li><b>retrying</b> — one whose last runs or batches failed and is backing off — is
 *       {@link org.sliceworkz.eventstore.observability.HealthStatus#DEGRADED DEGRADED} too, and clears by
 *       itself on its first success;</li>
 *   <li><b>stopped by an operator</b> is {@link org.sliceworkz.eventstore.observability.HealthStatus#UP UP}
 *       and counted: it was asked for, and alerting on it would page the person who asked.</li>
 * </ul>
 * The messages name the components concerned; the details count them.
 * <p>
 * Held to the storage's rules: <b>it never throws on account of the context</b> — a terminated one answers
 * {@link BoundedContextState#TERMINATED} with a storage report for the store it closed — <b>it returns
 * within its timeout</b>, whatever the storage does, and <b>it is neither observed nor cached</b>. Only the
 * storage's report and the processor lag leave the process, and both are bounded by the one timeout.
 * Like the rest of the operator surface, it reports on <em>the instance it is called on</em>.
 */
public interface HealthCapability {

	/**
	 * Where the context is in its lifecycle, read locally and at once: the cheap question a readiness
	 * probe can ask before, or instead of, {@link #health()}.
	 *
	 * @return the lifecycle state
	 */
	BoundedContextState state ( );

	/**
	 * The context's health, waiting at most {@link EventStorage#DEFAULT_HEALTH_TIMEOUT} for anything
	 * outside the process.
	 *
	 * @return the health
	 * @see #health(Duration)
	 */
	default BoundedContextHealth health ( ) {
		return health(EventStorage.DEFAULT_HEALTH_TIMEOUT);
	}

	/**
	 * The context's health, checked now.
	 *
	 * @param timeout how long to wait for the storage's report and the processor lag; positive
	 * @return the health
	 * @throws IllegalArgumentException for a timeout that is not positive
	 */
	BoundedContextHealth health ( Duration timeout );

}
