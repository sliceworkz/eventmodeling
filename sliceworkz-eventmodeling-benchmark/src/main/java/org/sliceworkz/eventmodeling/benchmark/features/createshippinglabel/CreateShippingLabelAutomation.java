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
package org.sliceworkz.eventmodeling.benchmark.features.createshippinglabel;

import java.util.Optional;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingBoundedContext;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.ShippingLabelCreated;
import org.sliceworkz.eventmodeling.benchmark.features.createshippinglabel.RequiredShippingLabels.RequiredShippingLabel;
import org.sliceworkz.eventstore.events.EventReference;

public class CreateShippingLabelAutomation implements Automation<OrderProcessingDomainEvent,RequiredShippingLabel> {
	
	private RequiredShippingLabels requiredShippingLabels; 
	private Supplier<OrderProcessingBoundedContext> context;
	
	public CreateShippingLabelAutomation ( RequiredShippingLabels requiredShippingLabels, Supplier<OrderProcessingBoundedContext> context ) {
		this.requiredShippingLabels = requiredShippingLabels;
		this.context = context;
	}
	
	
	@Override
	public TodoListReadModel<OrderProcessingDomainEvent, RequiredShippingLabel> getTodoList() {
		return requiredShippingLabels;
	}

	@Override
	public Optional<EventReference> handle(RequiredShippingLabel todoItem) {
		return Optional.of(context.get().event(new ShippingLabelCreated(todoItem.orderId())).reference());
	}


}
