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
package org.sliceworkz.eventmodeling.outbound;

import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * Sends outbound events to the outside world: the outbox of a bounded context. A dispatcher is handed
 * every outbound event its filter selects, in order, through {@link #when}, by a processor reading the
 * outbound stream forward from the dispatcher's bookmark, on the elected leader.
 * <p>
 * It declares a filter and not a query, and has no savepoint: a bookmarked reader always reads forward
 * from where it stopped, so a direction, a limit, a read bound or an {@code initQuery} would have nothing
 * to mean. Delivery is at least once: an event is handed over again when a crash lands between the
 * delivery and the bookmark, so whatever the dispatcher sends to should tolerate a repeat, keyed on the
 * event's id.
 *
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events in the bounded context
 */
public interface Dispatcher<OUTBOUND_EVENT_TYPE> extends EventHandler<OUTBOUND_EVENT_TYPE> {

	/**
	 * The outbound events this dispatcher sends.
	 *
	 * @return the filter selecting them from the outbound stream
	 */
	EventFilter eventFilter ( );

}
