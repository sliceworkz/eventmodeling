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
import java.util.concurrent.ConcurrentHashMap;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateEventAppender;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class AggregateEventAppenderImpl<DOMAIN_EVENT_TYPE> implements AggregateEventAppender<DOMAIN_EVENT_TYPE> {

	private List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events = new ArrayList<>();
	private Aggregate<DOMAIN_EVENT_TYPE> aggregate;
	private EventStream<DOMAIN_EVENT_TYPE> eventStream;
	private Tags identity;
	private EventReference lastReference;
	private String boundedContext;
	private Instance instance;
	private MeterRegistry meterRegistry;
	private ConcurrentHashMap<String, Counter> domainEventCounters;
	private Tracing tracing;

	public AggregateEventAppenderImpl ( EventStream<DOMAIN_EVENT_TYPE> eventStream, Aggregate<DOMAIN_EVENT_TYPE> aggregate, Tags identity, EventReference lastReference, String boundedContext, Instance instance, MeterRegistry meterRegistry, ConcurrentHashMap<String, Counter> domainEventCounters, Tracing tracing ) {
		this.eventStream = eventStream;
		this.aggregate = aggregate;
		this.identity = identity;
		this.lastReference = lastReference;
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.meterRegistry = meterRegistry;
		this.domainEventCounters = domainEventCounters;
		this.tracing = tracing;
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
		// Record metrics for each raised domain event with tracing tags
		String actor = (tracing != null && tracing.actor() != null) ? tracing.actor() : "unknown";
		String channel = (tracing != null && tracing.channel() != null) ? tracing.channel() : "unknown";
		for (EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event : events) {
			String eventName = event.data().getClass().getSimpleName();
			String cacheKey = eventName + ":" + actor + ":" + channel;

			Counter counter = domainEventCounters.computeIfAbsent(cacheKey, key ->
				meterRegistry.counter("sliceworkz.eventmodeling.domain.event",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "event", eventName, "actor", actor, "channel", channel, "source", "aggregate")));
			counter.increment();
		}

		EventReference lastEvent = eventStream.append(
				AppendCriteria.of(EventFilter.forEvents(EventTypesFilter.any(), identity), lastReference),
				events).stream().map(e->{this.lastReference=e.reference();return e;}).map(e->{aggregate.when(e);return e;}).map(Event::reference).reduce((one,two)->two).orElse(null);
		events.clear();
		return lastEvent;
	}
	
}
