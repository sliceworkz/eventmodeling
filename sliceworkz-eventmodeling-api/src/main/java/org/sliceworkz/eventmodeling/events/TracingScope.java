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
package org.sliceworkz.eventmodeling.events;

import java.util.Optional;

/**
 * The tracing of the work the calling thread is doing on behalf of an edge — typically the HTTP request it
 * serves — bound once, where that work enters the application, and picked up by everything the framework
 * does on the thread without a tracing of its own.
 * <p>
 * An edge binds it upfront, as it authenticates the caller, and closes it when the request is done:
 * <pre>{@code
 * try ( TracingScope scope = TracingScope.bind(Tracing.actorAndChannel(user, "api").correlationId(fromHeader)) ) {
 *     handle(request);
 * }
 * }</pre>
 * Two things read it, and both are what makes one request <em>one flow</em> — one correlation id on every
 * event it causes:
 * <ul>
 * <li><b>The no-tracing overloads</b> of the bounded context's capabilities ({@code execute(command)},
 * {@code read(...)}, {@code event(...)}, {@code incoming(...)}, {@code translate(...)}, {@code evaluate(...)},
 * {@code aggregate(...)}) use the bound tracing where they would otherwise mint a fresh one. An overload
 * handed a tracing uses what it was handed: an explicit tracing always wins.</li>
 * <li><b>A call through a monitored port</b> made where no component supplies a tracing — a REST endpoint a
 * feature slice wires in its {@code start...} methods, whose calls run on a request thread — reports its
 * {@code PortCalled}/{@code PortCallRejected}/{@code PortCallFailed} under the bound tracing, so the
 * membership check an endpoint makes before executing a command carries the command's correlation id
 * instead of one of its own.</li>
 * </ul>
 * The alternative — a correlation id per call, minted by each overload and each port call — loses because a
 * request that checks access, reads and then executes becomes three unrelated flows in the monitoring record,
 * which is exactly the question a correlation id exists to answer.
 * <p>
 * A binding belongs to the thread that made it, and is closed on that thread: {@link #close()} restores what
 * was bound before, so scopes nest. Nothing is inherited by another thread — a thread the request starts
 * itself, or the processor threads of read models, automations, translators and dispatchers, which carry the
 * tracing of the events they handle instead.
 */
public final class TracingScope implements AutoCloseable {

	private static final ThreadLocal<Tracing> BOUND = new ThreadLocal<>();

	private final Tracing previous;
	private final Thread owner;
	private boolean closed;

	private TracingScope ( Tracing previous ) {
		this.previous = previous;
		this.owner = Thread.currentThread();
	}

	/**
	 * Binds the given tracing to the calling thread until the returned scope is closed.
	 *
	 * @param tracing the tracing of the work this thread does from here on
	 * @return the scope to close when that work is done, on this thread
	 * @throws IllegalArgumentException for a {@code null} tracing: there is nothing to bind, and a binding
	 *         that silently bound nothing would hide the edge that forgot its tracing
	 */
	public static TracingScope bind ( Tracing tracing ) {
		if ( tracing == null ) {
			throw new IllegalArgumentException("a tracing scope needs a tracing to bind");
		}
		TracingScope scope = new TracingScope(BOUND.get());
		BOUND.set(tracing);
		return scope;
	}

	/**
	 * @return the tracing bound to the calling thread, empty when none is
	 */
	public static Optional<Tracing> current ( ) {
		return Optional.ofNullable(BOUND.get());
	}

	/**
	 * Restores what was bound before this scope. Idempotent; a close on another thread than the one that
	 * bound it does nothing, since that thread's binding is not this one's to restore.
	 */
	@Override
	public void close ( ) {
		if ( closed || Thread.currentThread() != owner ) {
			return;
		}
		closed = true;
		if ( previous == null ) {
			BOUND.remove();
		} else {
			BOUND.set(previous);
		}
	}

}
