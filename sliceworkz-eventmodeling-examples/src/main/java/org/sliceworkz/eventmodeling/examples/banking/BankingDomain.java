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
package org.sliceworkz.eventmodeling.examples.banking;

import java.time.LocalDate;
import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventstore.events.Tagged;
import org.sliceworkz.eventstore.events.Tags;


public interface BankingDomain {

	public record AccountId ( String value ) implements EntityId { }
	public record CustomerId ( String value ) implements EntityId { }

	public static final Entity<AccountId> ACCOUNT = Entity.of("account", AccountId::new);
	public static final Entity<CustomerId> CUSTOMER = Entity.of("customer", CustomerId::new);
	
	
	/** Every domain event states the tags it is about, so a command raises it without repeating them. */
	public sealed interface BankingDomainEvent extends Tagged {
		
		public record AccountOpened ( AccountId accountId, CustomerId customerId, LocalDate date ) implements BankingDomainEvent {
			@Override
			public Tags eventTags ( ) {
				return Tags.of(ACCOUNT.tag(accountId), CUSTOMER.tag(customerId));
			}
		}
		
	}

	
	public sealed interface BankingInboundEvent {
		
		public record CustomerSuspended ( CustomerId customerId ) implements BankingInboundEvent { } 
		
	}

	
	public sealed interface BankingOutboundEvent {
		
		public record AccountAnnounced ( AccountId id ) implements BankingOutboundEvent { } 
		
	}
	
}
