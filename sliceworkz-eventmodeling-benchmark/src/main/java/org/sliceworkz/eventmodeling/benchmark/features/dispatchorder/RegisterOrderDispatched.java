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
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent.OrderProcessed;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
import org.sliceworkz.eventstore.events.Tags;

public class RegisterOrderDispatched implements OutboundCommand<OrderProcessingDomainEvent, OrderProcessingOutboundEvent> {

	private long orderId;

	public RegisterOrderDispatched ( long orderId ) {
		this.orderId = orderId;
	}

	@Override
	public void execute(
			OutboundCommandContext<OrderProcessingDomainEvent, OrderProcessingOutboundEvent> context) {
		// the idempotency key comes from the caller — publishAndRecord derives it from the todo item
		context.noDecisionModels()
				.requireIdempotencyKey()
				.raiseEvent(new OrderProcessed(orderId), Tags.none());
	}

}
