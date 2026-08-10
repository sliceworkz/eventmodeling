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
 * The lifecycle of a bounded context: built → started ⇄ stopped → terminated.
 * <p>
 * {@code build()} assembles, {@link #start()} runs — a freshly built context has no processor threads
 * and does no work until started. {@link #stop()} parks the processors without releasing anything, and
 * {@code stop()} → {@code start()} is the supported restart path. {@link #terminate()} is terminal.
 * <p>
 * The guards a bounded context holds these methods to:
 * <ul>
 * <li>{@code start()} on a context that is already started is a no-op, logged at WARN — it does
 *     <em>not</em> re-emit the starting/started events, re-run the slice wiring, or block on the
 *     ephemeral read-model projection wait a real start does.</li>
 * <li>{@code start()} on a terminated context throws {@link IllegalStateException}: its
 *     {@code EventStore} is closed and its processor threads drained, so a context that "started"
 *     anyway would look alive and do nothing.</li>
 * <li>{@code stop()} on a context that is not started — never started, already stopped, or
 *     terminated — is a no-op.</li>
 * <li>{@code terminate()} is idempotent; later calls do nothing.</li>
 * </ul>
 */
public interface LifecycleCapability {

	/**
	 * Starts the processors and announces the context: emits the starting/started events, runs every
	 * deployed slice's wiring, holds one leader-election round, and blocks until the ephemeral read
	 * models have been projected. No-op with a WARN when already started; throws
	 * {@link IllegalStateException} after {@link #terminate()}.
	 */
	void start ( );

	/**
	 * Parks the processors and releases the held leases, so a standby instance takes over promptly.
	 * Nothing is released beyond that: the context can be {@link #start() started} again. No-op when
	 * not started.
	 */
	void stop ( );

	/**
	 * Shuts this down for good: stops the processors, drains their threads, and releases what it
	 * created — for a bounded context, the {@code EventStore} it built over the storage it was given.
	 * <p>
	 * Terminal, and idempotent. The event storage is <em>not</em> closed: it was supplied from outside,
	 * it can back other bounded contexts, and it usually outlives this one. Close it yourself after
	 * terminating every context on it (see
	 * {@link BoundedContextBuilder#eventStorage(org.sliceworkz.eventstore.spi.EventStorage)}).
	 * <p>
	 * A bounded context also terminates itself from a JVM shutdown hook, so a process that simply
	 * exits does not strand its threads; calling this explicitly is how a test or an application that
	 * outlives the context releases them earlier.
	 */
	void terminate ( );
	
}
