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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

public record CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> ( String boundedContext, EventStreamId targetStreamId, Tracing tracing, EventQuery eventQuery, Optional<EventReference> lastEventReference, List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> events ) 
implements CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	// TODO add monitoring & debugging metadata? (models used, events seen, timings, correlation id, actor / acting user, channel, ...)
	
	public CommandResultImpl ( String boundedContext, EventStreamId targetStreamId, Tracing tracing, EventQuery eventQuery, Optional<EventReference> lastEventReference, List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> events ) {
		this.boundedContext = boundedContext;
		this.targetStreamId = targetStreamId;
		this.tracing = tracing;
		this.eventQuery = eventQuery;
		this.lastEventReference = lastEventReference;
		this.events = events;
	}

	public CommandResultImpl ( String boundedContext, EventStreamId targetStreamId, Tracing tracing, EventQuery eventQuery, Optional<EventReference> lastEventReference ) {
		this(boundedContext, targetStreamId, tracing, eventQuery, lastEventReference, new ArrayList<>());
	}
	
	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags ) {
		events.add(tracing.storeOn(Event.of(event, tags)));
		return this;
	}
	
	public List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> raisedEvents ( ) {
		return events;
	}

	public AppendCriteria appendCriteria ( ) {
		return AppendCriteria.of(eventQuery, lastEventReference); 
	}
	
}
