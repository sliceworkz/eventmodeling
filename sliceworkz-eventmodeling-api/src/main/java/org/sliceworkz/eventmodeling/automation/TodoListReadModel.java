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
package org.sliceworkz.eventmodeling.automation;

import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.Limit;

public interface TodoListReadModel<EVENT_TYPE, TODO_ITEM_TYPE> extends ReadModelWithMetaData<EVENT_TYPE> {

	/**
	 * The outstanding work, oldest first, at most {@code limit} items.
	 * <p>
	 * Two obligations come with implementing this, neither of which the framework can check:
	 * <ul>
	 *   <li><strong>Order.</strong> Items are handled in the order they are returned in. Where the
	 *       automation's work has to happen in a particular order, this method has to return them in
	 *       that order — the framework never re-orders and never verifies.</li>
	 *   <li><strong>Stability.</strong> The stream is consumed one item at a time, and the item after
	 *       the current one is only taken once the current one has been handled. The state behind it may
	 *       therefore be read while the automation is running, so it must not be a live view that the
	 *       todo list's own projector can mutate underneath the consumer.</li>
	 * </ul>
	 * That second point is also an opportunity. Because the next item is taken late, an implementation
	 * may <em>anticipate</em> its own projection: when handling one item raises an event that cancels or
	 * supersedes the items behind it, the automation can tell its todo list so, and the items withheld
	 * here are never handled — without waiting for the projector to catch up. Anticipation is only ever
	 * safe as a shortcut to a conclusion the projection reaches on its own from the events that were
	 * raised. A todo list is rebuilt from events on every restart, so anything decided here and recorded
	 * nowhere else is undone by the next one.
	 *
	 * @param limit the maximum number of items to return, as set by the automation's batch size
	 * @return the outstanding items, in handling order
	 */
	Stream<TODO_ITEM_TYPE> streamItems ( Limit limit );

	Optional<EventReference> lastEventReference ( );
	
}
