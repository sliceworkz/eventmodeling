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
package org.sliceworkz.eventmodeling.benchmark;

public interface OrderProcessingEvent {
	
	public sealed interface OrderProcessingInboundEvent {
		
		// This Incoming/inbound event gets OrderProcessing started
		public record OrderRegistered ( long orderId ) implements OrderProcessingInboundEvent { }
		
	}
	
	public sealed interface OrderProcessingDomainEvent {
		
		// OrderPlacedEvent (Inbound) is translated into this Domain event
		public record OrderReceived ( long orderId ) implements OrderProcessingDomainEvent { }
		
		// First step in the process ...
		public record ShippingLabelCreated ( long orderId ) implements OrderProcessingDomainEvent { }

		
		// ... then these two go in parallel ...
		public record OrderPackaged ( long orderId ) implements OrderProcessingDomainEvent { }
		
		public record ShipmentAnnounced  ( long orderId ) implements OrderProcessingDomainEvent { };

		
		// ... and this one depends on the previous two
		public record OrderDispatched ( long orderId ) implements OrderProcessingDomainEvent { }
		
	}

	public sealed interface OrderProcessingOutboundEvent {
		
		// This outbound event is raised when all work on the order is done
		public record OrderProcessed ( long orderId ) implements OrderProcessingOutboundEvent { }

	}

}
