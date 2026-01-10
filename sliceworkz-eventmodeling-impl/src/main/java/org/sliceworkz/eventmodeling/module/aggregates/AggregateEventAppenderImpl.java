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

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateEventAppender;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

public class AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> implements AggregateEventAppender<DOMAIN_EVENT_TYPE> {

	private List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events = new ArrayList<>();
	private Aggregate<DOMAIN_EVENT_TYPE> aggregate;
	private EventStream<DOMAIN_EVENT_TYPE> eventStream;
	private Tags identity;
	private EventReference lastReference;
	
	public AggregateEventAppenderImpl ( EventStream<DOMAIN_EVENT_TYPE> eventStream, Aggregate<DOMAIN_EVENT_TYPE> aggregate, Tags identity, EventReference lastReference ) {
		this.eventStream = eventStream;
		this.aggregate = aggregate;
		this.identity = identity;
		this.lastReference = lastReference; 
	}
	
	@Override
	public AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> add(DOMAIN_EVENT_TYPE event) {
		return add(event, null);
	}

	@Override
	public AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> add(DOMAIN_EVENT_TYPE event, String idempotencyKey) {
		events.add(Event.of(event, identity).withIdempotencyKey(idempotencyKey));
		return this;
	}

	@Override
	public EventReference append() {
		EventReference lastEvent = eventStream.append(
				AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.any(), identity), lastReference),
				events).stream().map(e->{this.lastReference=e.reference();return e;}).map(e->{aggregate.when(e);return e;}).map(Event::reference).reduce((one,two)->two).orElse(null);
		events.clear();
		return lastEvent;
	}
	
}
