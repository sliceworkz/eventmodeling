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
package org.sliceworkz.eventmodeling.examples.banking.features.accountoverview;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.query.EventQuery;

public class AccountOverviewReadModel implements ReadModel<BankingDomainEvent> {

	public static AccountOverviewReadModel INSTANCE = new AccountOverviewReadModel();

	private Set<AccountSummary> accounts = new HashSet<>();
	
	@Override
	public EventQuery eventQuery() {
		return EventQuery.matchAll();
	}
	
	public Set<AccountSummary> getAccounts ( ) {
		return accounts;
	}

	@Override
	public void when(BankingDomainEvent event) {
		
		switch ( event ) {
			case AccountOpened ao: 
				this.accounts.add(new AccountSummary(ao.accountId().value(), ao.customerId().value(), ao.date()));  
				break;
		}
		
	}
	
	public record AccountSummary ( String id, String customerId, LocalDate openDate ) {
		
	}
	
}
