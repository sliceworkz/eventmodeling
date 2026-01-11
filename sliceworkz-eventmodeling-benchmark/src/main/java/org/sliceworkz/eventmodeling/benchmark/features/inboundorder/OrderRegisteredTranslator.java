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
package org.sliceworkz.eventmodeling.benchmark.features.inboundorder;

import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.OrderReceived;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent.OrderRegistered;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

public class OrderRegisteredTranslator implements Translator<OrderProcessingInboundEvent,OrderProcessingDomainEvent> {
	
	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(EventTypesFilter.of(OrderRegistered.class), Tags.none());
	}

	@Override
	public void translate(OrderProcessingInboundEvent event, TranslatorContext<OrderProcessingInboundEvent,OrderProcessingDomainEvent> context) {
		switch(event) {
			case OrderRegistered or -> context.event(new OrderReceived(or.orderId()));
			default -> { }
		}
		
	}

}
