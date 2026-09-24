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
package org.sliceworkz.eventmodeling.module.aggregates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateEventAppender;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> implements AggregateEventAppender<DOMAIN_EVENT_TYPE> {

	private List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events = new ArrayList<>();
	private Aggregate<DOMAIN_EVENT_TYPE> aggregate;
	private EventStream<DOMAIN_EVENT_TYPE> eventStream;
	private Tags identity;
	private EventReference lastReference;
	private String boundedContext;
	private String aggregateName;
	private BoundedContextObserver observer;
	private Tracing tracing;

	public AggregateEventAppenderImpl ( EventStream<DOMAIN_EVENT_TYPE> eventStream, Aggregate<DOMAIN_EVENT_TYPE> aggregate, Tags identity, EventReference lastReference, String boundedContext, String aggregateName, BoundedContextObserver observer, Tracing tracing ) {
		this.eventStream = eventStream;
		this.aggregate = aggregate;
		this.identity = identity;
		this.lastReference = lastReference;
		this.boundedContext = boundedContext;
		this.aggregateName = aggregateName;
		this.observer = observer;
		this.tracing = tracing;
	}
	
	@Override
	public AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> add(DOMAIN_EVENT_TYPE event) {
		return add(event, null);
	}

	@Override
	public AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> add(DOMAIN_EVENT_TYPE event, String idempotencyKey) {
		EphemeralEvent<DOMAIN_EVENT_TYPE> ephemeralEvent = Event.of(event, identity).withIdempotencyKey(idempotencyKey);
		if ( tracing != null ) {
			ephemeralEvent = tracing.storeOn(ephemeralEvent);
		}
		events.add(ephemeralEvent);
		return this;
	}

	@Override
	public EventReference append() {
		Map<EventType,Integer> raisedPerType = new LinkedHashMap<>();
		for ( EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event : events ) {
			raisedPerType.merge(EventType.of(event.data().getClass()), 1, Integer::sum);
		}

		try ( Observation.Scope<Outcome.AppendResult> scope = observer.start(new Observation.AggregateAppend(boundedContext, aggregateName, identity, raisedPerType, tracing)) ) {
			try {
				List<EventReference> appended = eventStream.append(
						AppendCriteria.of(EventFilter.forEvents(EventTypesFilter.any(), identity), lastReference),
						events).stream().map(e->{this.lastReference=e.reference();return e;}).map(e->{aggregate.when(e);return e;}).map(Event::reference).toList();
				events.clear();
				scope.completed(new Outcome.Appended(appended));
				return appended.isEmpty() ? null : appended.getLast();
			} catch ( OptimisticLockingException ole ) {
				Optional<EventReference> expected = ole.getExpectedLastEventReference();
				scope.completed(new Outcome.Conflicted(expected != null ? expected : Optional.empty()));
				throw ole;
			} catch ( RuntimeException e ) {
				scope.failed(e);
				throw e;
			}
		}
	}
	
}
