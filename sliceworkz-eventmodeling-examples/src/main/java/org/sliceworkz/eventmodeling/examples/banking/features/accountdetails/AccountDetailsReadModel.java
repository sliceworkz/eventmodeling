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
package org.sliceworkz.eventmodeling.examples.banking.features.accountdetails;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomain;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

public class AccountDetailsReadModel implements ReadModel<BankingDomainEvent> {

	private AccountId accountId;
	private AccountDetails account;
	
	public AccountDetailsReadModel ( AccountId accountId ) {
		this.accountId = accountId;
	}
	
	public Optional<AccountDetails> getAccountDetails ( ) {
		return Optional.ofNullable(account);
	}
	
	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(EventTypesFilter.any(), BankingDomain.ACCOUNT.tags(accountId));
	}

	@Override
	public void when ( Event<BankingDomainEvent> event ) {
		
		switch ( event.data() ) {
			case AccountOpened ao: 
				this.account = AccountDetails.of(ao.accountId().value(), ao.customerId().value(), ao.date());  
				break;
		}
	}
	
	/**
	 * What a read of this model hands back. It is built by {@link #when} from an event and never
	 * persisted, so nothing reconstructs it from stored JSON, and a strict constructor costs nothing:
	 * this is the case {@code WHERE-VALIDATIONS-GO.md} calls "a record nothing ever persists". A
	 * record carried <em>inside</em> an event payload is the opposite case, and keeps a lenient
	 * canonical constructor, since Jackson rebuilds it on every read of history.
	 */
	public record AccountDetails ( String accountId, String customerId, LocalDate openDate ) {

		public AccountDetails {
			Objects.requireNonNull(accountId, "accountId is required");
			Objects.requireNonNull(customerId, "customerId is required");
			Objects.requireNonNull(openDate, "openDate is required");
		}

		public static AccountDetails of ( String accountId, String customerId, LocalDate openDate ) {
			return new AccountDetails(accountId, customerId, openDate);
		}

	}

}
