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
package org.sliceworkz.eventmodeling.examples.banking.features.openaccountwithresult;

import java.time.LocalDate;

import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.CustomerId;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Opens a new bank account and returns the generated account ID directly to the caller.
 * <p>
 * This is the {@link CommandWithResult} variant of
 * {@link org.sliceworkz.eventmodeling.examples.banking.features.openaccount.OpenAccountCommand OpenAccountCommand}.
 * Instead of requiring the caller to query the event stream to discover the generated account ID,
 * the ID is returned synchronously after events are persisted.
 */
public class OpenAccountWithResultCommand implements CommandWithResult<BankingDomainEvent, AccountId> {

	private final CustomerId customerId;

	public OpenAccountWithResultCommand ( CustomerId customerId ) {
		this.customerId = customerId;
	}

	@Override
	public AccountId execute ( CommandContext<BankingDomainEvent, BankingDomainEvent> context ) {

		var result = context.noDecisionModels();

		AccountId accountId = BankingDomain.ACCOUNT.newId();

		result.raiseEvent(new AccountOpened(accountId, customerId, LocalDate.now()),
				Tags.of(
						BankingDomain.ACCOUNT.tag(accountId),
						BankingDomain.CUSTOMER.tag(customerId)
				)
			);

		return accountId;
	}

}
