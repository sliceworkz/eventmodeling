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
package org.sliceworkz.eventmodeling.benchmark.features.dispatchorder;

import java.util.Optional;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingBoundedContext;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.OrderDispatched;
import org.sliceworkz.eventmodeling.benchmark.features.dispatchorder.OrdersReadyToDispatch.OrderReadyToDispatch;
import org.sliceworkz.eventstore.events.EventReference;

public class DispatchOrderAutomation implements Automation<OrderProcessingDomainEvent,OrderReadyToDispatch>{

	private OrdersReadyToDispatch ordersReadyToDispatch;
	private Supplier<OrderProcessingBoundedContext> context;
	
	public DispatchOrderAutomation ( OrdersReadyToDispatch ordersReadyToDispatch, Supplier<OrderProcessingBoundedContext> context ) {
		this.ordersReadyToDispatch = ordersReadyToDispatch;
		this.context = context;
	}
	
	@Override
	public TodoListReadModel<OrderProcessingDomainEvent, OrderReadyToDispatch> getTodoList() {
		return ordersReadyToDispatch;
	}

	@Override
	public Optional<EventReference> handle(OrderReadyToDispatch todoItem) {

		// first, execute the command (with idempotency)
		context.get().execute(new RegisterOrderDispatched(todoItem.orderId()));
		
		// then note down that this has happened as a domain event
		return Optional.of(context.get().event(new OrderDispatched(todoItem.orderId())).reference());
	}

}
