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
package org.sliceworkz.eventmodeling.examples.banking.features.openaccount;

import java.time.LocalDate;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventstore.events.Tags;

public class OpenAccountCommand implements Command<BankingDomainEvent> {
	
	private DomainConceptId customerId;
	
	public OpenAccountCommand ( DomainConceptId customerId ) {
		this.customerId = customerId;
	}

	@Override
	public CommandResult<BankingDomainEvent, BankingDomainEvent> execute(CommandContext<BankingDomainEvent, BankingDomainEvent> context) {
		
		var result = context.noDecisionModels();
		
		DomainConceptId accountId = DomainConceptId.create();
		
		return result.raiseEvent(new AccountOpened(accountId, customerId, LocalDate.now()), 
				Tags.of(
						DomainConceptTag.of(BankingDomain.CONCEPT_ACCOUNT, accountId),
						DomainConceptTag.of(BankingDomain.CONCEPT_CUSTOMER, customerId)
				)
			);
	}
	
}
