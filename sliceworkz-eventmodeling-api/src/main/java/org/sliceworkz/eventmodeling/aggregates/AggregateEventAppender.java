/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.aggregates;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Provides a fluent API for appending events to an aggregate's event stream.
 * <p>
 * This interface follows the builder pattern, allowing multiple events to be staged
 * and then appended to the event stream in a single atomic operation. Events are
 * appended with optimistic concurrency control to ensure consistency.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface AggregateEventAppender<DOMAIN_EVENT_TYPE> {

	/**
	 * Stages an event to be appended to the aggregate's event stream.
	 * <p>
	 * The event is not persisted until {@link #append()} is called.
	 *
	 * @param event the domain event to stage for appending
	 * @return this appender for method chaining
	 */
	AggregateEventAppender<DOMAIN_EVENT_TYPE> add ( DOMAIN_EVENT_TYPE event );

	/**
	 * Stages an event with an idempotency key to be appended to the aggregate's event stream.
	 * <p>
	 * The idempotency key ensures that if the same event is appended multiple times
	 * (e.g., due to retries), only the first occurrence will be persisted.
	 * The event is not persisted until {@link #append()} is called.
	 *
	 * @param event the domain event to stage for appending
	 * @param idempotencyKey unique key to prevent duplicate event appends, may be null
	 * @return this appender for method chaining
	 */
	AggregateEventAppender<DOMAIN_EVENT_TYPE> add ( DOMAIN_EVENT_TYPE event, String idempotencyKey );

	/**
	 * Appends all staged events to the aggregate's event stream.
	 * <p>
	 * This operation is atomic - either all staged events are persisted successfully,
	 * or none are. The append includes optimistic concurrency control to ensure the
	 * aggregate's event stream hasn't been modified by another process since it was loaded.
	 * After appending, the staged events list is cleared.
	 *
	 * @return reference to the last appended event, or null if no events were staged
	 */
	EventReference append ( );

}
