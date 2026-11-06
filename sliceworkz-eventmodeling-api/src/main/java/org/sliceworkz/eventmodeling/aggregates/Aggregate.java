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
package org.sliceworkz.eventmodeling.aggregates;

import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;

public interface Aggregate<DOMAIN_EVENT_TYPE> extends EventHandler<DOMAIN_EVENT_TYPE> {
	
	void setContext ( AggregateContext<DOMAIN_EVENT_TYPE> aggregateContext );
	
	/**
	 * The events this aggregate is made of, for the identity it is loaded under. It decides both what
	 * the aggregate reads and what its append checks: an event matching it that lands after the load
	 * fails the append with an {@code OptimisticLockingException}, and one that does not match is
	 * neither read nor checked.
	 * <p>
	 * The default — every event carrying the identity's tags — is the classical aggregate: everything
	 * about it is its history, and anything new about it conflicts. Narrow it only to leave out events
	 * that can change none of the aggregate's decisions, since an event left out is invisible to it
	 * twice over.
	 *
	 * @param identity the tags the aggregate was loaded under
	 * @return the filter selecting its events, read forward from the start or from its snapshot
	 */
	default EventFilter eventFilter ( Tags identity ) {
		return EventFilter.forTags(identity);
	}

}
