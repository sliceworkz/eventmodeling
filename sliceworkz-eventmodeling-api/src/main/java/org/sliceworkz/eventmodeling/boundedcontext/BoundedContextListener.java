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
