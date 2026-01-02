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
package org.sliceworkz.eventmodeling.module.aggregates;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.query.EventQuery;

public class ProjectionTowardsAggregate<DOMAIN_EVENT_TYPE> implements Projection<DOMAIN_EVENT_TYPE> {

	private Aggregate<DOMAIN_EVENT_TYPE> aggregate;
	private Tags identity;
	
	public ProjectionTowardsAggregate ( Aggregate<DOMAIN_EVENT_TYPE> aggregate, Tags identity ) {
		this.aggregate = aggregate;
		this.identity = identity;
	}
	
	@Override
	public void when(Event<DOMAIN_EVENT_TYPE> event) {
		this.aggregate.when(event);
	}

	@Override
	public EventQuery eventQuery() {
		return aggregate.eventQuery(identity);
	}

}
