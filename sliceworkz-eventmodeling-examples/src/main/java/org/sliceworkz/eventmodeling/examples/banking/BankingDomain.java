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
package org.sliceworkz.eventmodeling.examples.banking;

import java.time.LocalDate;

import org.sliceworkz.eventmodeling.domain.DomainConcept;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;

public interface BankingDomain {

	public static final DomainConcept CONCEPT_ACCOUNT = DomainConcept.of("account");
	public static final DomainConcept CONCEPT_CUSTOMER = DomainConcept.of("customer");
	
	
	public sealed interface BankingDomainEvent {
		
		public record AccountOpened ( DomainConceptId accountId, DomainConceptId customerId, LocalDate date ) implements BankingDomainEvent { } 
		
	}

	
	public sealed interface BankingInboundEvent {
		
		public record CustomerSuspended ( DomainConceptId customerId ) implements BankingInboundEvent { } 
		
	}

	
	public sealed interface BankingOutboundEvent {
		
		public record AccountAnnounced ( DomainConceptId id ) implements BankingOutboundEvent { } 
		
	}
	
}
