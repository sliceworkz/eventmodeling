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

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingFeatureSlice;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;

@FeatureSlice(type = Type.AUTOMATION)
public class DispatchOrderFeatureSlice implements OrderProcessingFeatureSlice {
	
	private OrdersReadyToDispatch ordersReadyToDispatch;

	@Override
	public void preConfigure(DataSource dataSource, boolean initializeDatabase) {
		ordersReadyToDispatch = new OrdersReadyToDispatch(dataSource);
		if (initializeDatabase) {
			this.ordersReadyToDispatch.initialize();
		}
	}

	@Override
	public void configureAutomation(
			BoundedContextBuilder<OrderProcessingDomainEvent, OrderProcessingInboundEvent, OrderProcessingOutboundEvent> builder) {
		builder.readmodel(ordersReadyToDispatch);
		builder.automation(new DispatchOrderAutomation(ordersReadyToDispatch));
	}

}
