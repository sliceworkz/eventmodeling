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

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.benchmark.OrderProcessingBoundedContext;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingFeatureSlice;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;

@FeatureSlice(type = Type.AUTOMATION)
public class CreateShippingLabelFeatureSlice implements OrderProcessingFeatureSlice{

	private RequiredShippingLabels requiredShippingLabels;
	private OrderProcessingBoundedContext boundedContext;
	private DataSource dataSource;
	
	@Override
	public void configure(
			BoundedContextBuilder<OrderProcessingDomainEvent, OrderProcessingInboundEvent, OrderProcessingOutboundEvent> builder) {
		requiredShippingLabels = new RequiredShippingLabels(()->dataSource);
		builder.readmodel(requiredShippingLabels);
		builder.automation(new CreateShippingLabelAutomation(requiredShippingLabels, ()->boundedContext));
	}

	@Override
	public void configure(OrderProcessingBoundedContext boundedContext, DataSource dataSource, boolean initializeDatabase) {
		this.dataSource = dataSource;
		this.boundedContext = boundedContext;
		if ( initializeDatabase ) {
			this.requiredShippingLabels.initialize();
		}
	}

}
