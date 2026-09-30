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
package org.sliceworkz.eventmodeling.benchmark.features.dispatchorder;

import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.OrderDispatched;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent.OrderProcessed;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Tells the world an order is processed, once the automation has recorded that it was dispatched: the
 * domain event is the fact, and this maps it into the published language. Keyed by the domain event, so a
 * redelivery publishes nothing twice.
 */
public class OrderDispatchedPublisher implements Publisher<OrderProcessingDomainEvent, OrderProcessingOutboundEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forTypes(OrderDispatched.class);
	}

	@Override
	public void publish ( Event<OrderProcessingDomainEvent> event, PublisherContext<OrderProcessingDomainEvent, OrderProcessingOutboundEvent> context ) {
		if ( event.data() instanceof OrderDispatched dispatched ) {
			context.publish(new OrderProcessed(dispatched.orderId()), Tags.none());
		}
	}

}
