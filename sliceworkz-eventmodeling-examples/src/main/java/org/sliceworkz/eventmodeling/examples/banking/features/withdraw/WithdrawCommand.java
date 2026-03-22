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
package org.sliceworkz.eventmodeling.examples.banking.features.withdraw;

import java.math.BigDecimal;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Withdraws money from the account's currently active period.
 * <p>
 * Enforces:
 * <ul>
 *   <li>Account must exist</li>
 *   <li>Current period must be open (discovered via initQuery)</li>
 *   <li>Sufficient balance</li>
 * </ul>
 */
public class WithdrawCommand implements Command<BankingEvent> {

	private final DomainConceptId accountId;
	private final BigDecimal amount;
	private final String description;

	public WithdrawCommand(DomainConceptId accountId, BigDecimal amount, String description) {
		this.accountId = accountId;
		this.amount = amount;
		this.description = description;
	}

	@Override
	public void execute(CommandContext<BankingEvent, BankingEvent> context) {

		var period = new ActivePeriodDecisionModel(accountId);
		var result = context.decisionModels(period);

		if (!period.accountExists()) {
			throw new IllegalStateException("Account does not exist");
		}
		if (period.isPeriodClosed()) {
			throw new IllegalStateException(
				"Period " + period.activeMonth() + " is closed, cannot withdraw");
		}
		if (period.balance().compareTo(amount) < 0) {
			throw new IllegalStateException(
				"Insufficient balance: " + period.balance() + " < " + amount);
		}

		result.raiseEvent(
			new MoneyWithdrawn(accountId, period.activeMonth(), amount, description),
			Tags.of(
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId),
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_MONTH,
					new DomainConceptId(period.activeMonth().toString()))
			)
		);
	}
}
