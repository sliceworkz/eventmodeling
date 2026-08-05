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

import org.sliceworkz.eventstore.events.EphemeralEvent;

/**
 * Listener notified of {@link BoundedContextEvent}s produced by the kernel of a bounded context.
 * <p>
 * Register an implementation on the {@link BoundedContextBuilder#listener(BoundedContextListener)};
 * the implementation decides what to do with each event (append it to an event stream, log it,
 * forward it to a monitoring system, ...).
 * <p>
 * The event is delivered as an {@link EphemeralEvent} that already carries the relevant tracing
 * tags (instance, actor, channel, ...), so it can be appended to an
 * {@link org.sliceworkz.eventstore.stream.EventStream} directly, or inspected via
 * {@link EphemeralEvent#data()}.
 * <p>
 * <strong>Threading:</strong> the listener is invoked synchronously on the thread performing the
 * operation. Implementations must be fast and thread-safe, or buffer/offload work asynchronously.
 * <p>
 * <strong>A failure here never fails the operation being observed.</strong> An exception escaping
 * {@link #on(EphemeralEvent)} is contained by the kernel, logged at ERROR and counted on the
 * {@code sliceworkz.eventmodeling.listener.failure} meter; the command, automation batch or
 * projection that produced the event carries on exactly as if no listener were registered. This
 * matters most where the event is emitted <em>after</em> the work is already durable — a
 * {@link BoundedContextEvent.CommandExecuted} whose delivery threw would otherwise report a
 * succeeded command as failed and invite the caller to run it twice.
 * <p>
 * The corollary is that <strong>nothing replays what a failing listener missed</strong>: the event
 * is dropped, and the next one is delivered normally. A listener that must not lose events is
 * responsible for its own durability - buffer and retry inside the implementation, or accept that
 * the stream it writes is a best-effort observability record rather than a complete one.
 * <p>
 * {@link Error} is deliberately not contained, matching the event store's rule for its own append
 * listeners: an exhausted heap is not a listener problem to absorb.
 *
 * @see StreamAppendingBoundedContextListener
 * @see LoggingBoundedContextListener
 */
@FunctionalInterface
public interface BoundedContextListener {

	/**
	 * Called for every {@link BoundedContextEvent} produced by the bounded context.
	 *
	 * @param event the event, with tracing tags attached
	 */
	void on ( EphemeralEvent<BoundedContextEvent> event );

	/**
	 * A listener that ignores all events. This is the default when none is registered, and is used
	 * as a sentinel so the kernel can skip producing events entirely (zero overhead).
	 */
	BoundedContextListener NO_OP = event -> { };

}
