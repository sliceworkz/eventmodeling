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

/**
 * Where a bounded context is in its lifecycle, as {@link HealthCapability#state()} answers it.
 * <p>
 * {@code BUILT → STARTING → RUNNING ⇄ STOPPED → TERMINATED}. {@link #STARTING} is its own state because a
 * start is not over until every in-memory read model has been projected, which on a long history takes
 * minutes: a context in that window has its slices wired and its processors running, and is not yet fit to
 * serve an eventually consistent read. {@link LifecycleCapability#start()} waits it out;
 * {@link LifecycleCapability#startInBackground()} returns into it, so a process can answer its probes while
 * it lasts. A readiness probe waits for {@link #RUNNING}; liveness holds throughout.
 */
public enum BoundedContextState {

	/** Built, never started: nothing runs. */
	BUILT,

	/** A start is in progress, waiting for the in-memory read models to be projected. */
	STARTING,

	/** Started, its in-memory read models projected (or the wait for them timed out), and neither {@code stop()} nor {@code terminate()} called since. */
	RUNNING,

	/** Stopped: its processors are parked and its leases released; {@code start()} brings it back. */
	STOPPED,

	/** Terminated: its store is closed and its threads are gone, for good. */
	TERMINATED

}
