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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Publishes every {@link FirstDomainEvent} as a {@link SomeOutboundEvent} carrying the same value: the
 * simplest publisher there is, for tests about what happens around a publication.
 */
public class MockPublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
	}

	@Override
	public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) {
		if ( event.data() instanceof FirstDomainEvent first ) {
			context.publish(new SomeOutboundEvent(first.value()), Tags.none());
		}
	}

}
