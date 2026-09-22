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
package org.sliceworkz.eventmodeling.examples.banking.features.deposit;

import java.math.BigDecimal;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Deposits money into the account's currently active period.
 * <p>
 * Uses the {@link ActivePeriodDecisionModel} to determine:
 * <ul>
 *   <li>Whether the account exists</li>
 *   <li>Which month is currently active (discovered via initQuery)</li>
 *   <li>Whether the current period is still open (rejects deposits to closed periods)</li>
 * </ul>
 *
 * <p>The raised event is tagged with both account AND month, so it will be
 * filtered correctly when loading a specific period's events.</p>
 *
 * <p>A rule that fails is a {@link BusinessException}: history says no, which is an outcome of
 * the command and not a bug.</p>
 */
public class DepositCommand implements Command<BankingEvent> {

	private final AccountId accountId;
	private final BigDecimal amount;
	private final String description;

	public DepositCommand(AccountId accountId, BigDecimal amount, String description) {
		this.accountId = accountId;
		this.amount = amount;
		this.description = description;
	}

	@Override
	public CommandResult<BankingEvent, BankingEvent> execute(CommandContext<BankingEvent, BankingEvent> context) {

		var period = new ActivePeriodDecisionModel(accountId);
		var result = context.decisionModels(period);

		BusinessException.when(!period.accountExists(), "Account does not exist");
		BusinessException.when(period.isPeriodClosed(),
			"Period " + period.activeMonth() + " is closed, cannot deposit");

		// Tag with both account identity AND period identity
		return result.raiseEvent(
			new MoneyDeposited(accountId, period.activeMonth(), amount, description),
			Tags.of(
				BankingDomainWithClosingTheBooks.ACCOUNT.tag(accountId),
				BankingDomainWithClosingTheBooks.MONTH.tag(BankingDomainWithClosingTheBooks.monthId(period.activeMonth()))
			)
		);
	}
}
