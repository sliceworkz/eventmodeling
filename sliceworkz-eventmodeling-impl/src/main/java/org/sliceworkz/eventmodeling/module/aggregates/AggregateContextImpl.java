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

import java.time.Instant;
import java.util.List;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.aggregates.AggregateEventAppender;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger.Metrics;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventStream;

public class AggregateContextImpl<DOMAIN_EVENT_TYPE> implements AggregateContext<DOMAIN_EVENT_TYPE> {

	private String boundedContext;
	private Instance instance;
	private Tags identity;
	private Aggregate<DOMAIN_EVENT_TYPE> aggregate;
	private EventStream<DOMAIN_EVENT_TYPE> eventStream;
	private Projection<DOMAIN_EVENT_TYPE> projectionTowardsAggregate;
	private EventReference lastEventReference;
	private AggregateEventAppender<DOMAIN_EVENT_TYPE> aggregateEventAppender;
	
	public AggregateContextImpl ( String boundedContext, Instance instance, Tags identity, Aggregate<DOMAIN_EVENT_TYPE> aggregate, EventStream<DOMAIN_EVENT_TYPE> eventStream ) {
		this.boundedContext = boundedContext;
		this.instance = instance;	
		this.identity = identity;
		this.aggregate = aggregate;
		this.eventStream = eventStream;
		this.projectionTowardsAggregate = new ProjectionTowardsAggregate<>(aggregate, identity);
		this.aggregateEventAppender = new AggregateEventAppenderImpl<>(eventStream, aggregate, identity, null);
	}
	
	@Override
	public Tags identity() {
		return identity;
	}

	@Override
	public void raiseEvent(DOMAIN_EVENT_TYPE event) {
		raiseEvent(event, null);
	}

	@Override
	public void raiseEvent(DOMAIN_EVENT_TYPE event, String idempotencyKey) {
		eventAppender().add(event, idempotencyKey).append();
	}

	@Override
	public void raiseEvents(List<DOMAIN_EVENT_TYPE> events) {
		var eventAppender = eventAppender();
		events.stream().map(eventAppender::add);
		eventAppender.append();
	}

	@Override
	public AggregateEventAppender<DOMAIN_EVENT_TYPE> eventAppender() {
		return aggregateEventAppender;
	}

	@Override
	public void updateFromStream() {
		Instant start = Instant.now();
		
		
		ProjectorMetrics projectorMetrics = Projector.from(eventStream).towards(projectionTowardsAggregate).startingAfter(lastEventReference).build().run();
		this.lastEventReference = projectorMetrics.lastEventReference();
		this.aggregateEventAppender = new AggregateEventAppenderImpl<>(eventStream, aggregate, identity, lastEventReference);
		Instant finish = Instant.now();
		
		long duration = finish.toEpochMilli() - start.toEpochMilli();
		Metrics metrics = new Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
		
		PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("aggregate.load").aggregate(aggregate.getClass().getSimpleName()).log();
	}
	
}
