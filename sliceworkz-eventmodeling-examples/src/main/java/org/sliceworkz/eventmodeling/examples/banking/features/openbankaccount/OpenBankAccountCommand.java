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
package org.sliceworkz.eventmodeling.examples.banking.features.openbankaccount;

import java.time.LocalDate;
import java.time.YearMonth;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Opens a new bank account and starts the first period.
 * <p>
 * This is the entry point for the "Closing The Books" lifecycle.
 * The {@code initialMonth} determines which monthly period the account
 * starts in. All subsequent deposits and withdrawals happen within this
 * period until the books are closed.
 * <p>
 * The raised {@link AccountOpened} event is tagged with both the account
 * identity and the initial month, so it appears when querying events
 * for that specific period.
 */
public class OpenBankAccountCommand implements Command<BankingEvent> {

	private final DomainConceptId customerId;
	private final YearMonth initialMonth;

	public OpenBankAccountCommand(DomainConceptId customerId, YearMonth initialMonth) {
		this.customerId = customerId;
		this.initialMonth = initialMonth;
	}

	@Override
	public void execute(CommandContext<BankingEvent, BankingEvent> context) {

		var result = context.noDecisionModels();

		DomainConceptId accountId = DomainConceptId.create();

		result.raiseEvent(
			new AccountOpened(accountId, customerId, initialMonth, LocalDate.now()),
			Tags.of(
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId),
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_MONTH,
					new DomainConceptId(initialMonth.toString()))
			)
		);
	}
}
