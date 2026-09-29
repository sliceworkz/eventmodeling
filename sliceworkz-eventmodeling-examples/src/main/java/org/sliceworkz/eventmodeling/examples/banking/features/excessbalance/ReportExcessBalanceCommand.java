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
package org.sliceworkz.eventmodeling.examples.banking.features.excessbalance;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BALANCE_WITHIN_GUARANTEE;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.RulebookFollowUp.ExcessBalanceReported;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventstore.events.EventId;

/**
 * Reports to the customer that the balance of the account exceeds what the deposit guarantee covers: the
 * enforcement of {@code balance-within-guarantee} that {@code DepositCommand} deferred.
 * <p>
 * Raised with an idempotency key derived from the deposit it enforces for, never from the attempt, so an
 * automation handing the same item over twice (a crash between the append and its bookmark) reports once.
 * The report carries the balance as it is when the report is made, which is what the customer is told about, and
 * {@code context.enforces(...)} links it to the deposit whose enforcement was deferred.
 *
 * @param accountId the account
 * @param deposit the id of the deposit whose enforcement was deferred
 */
public record ReportExcessBalanceCommand ( AccountId accountId, String deposit ) implements Command<BankingEvent> {

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
		var period = new ActivePeriodDecisionModel(accountId);
		var result = context.decisionModels(period);

		BusinessException.when(!period.accountExists(), "Account does not exist");

		result.raiseEvent(new ExcessBalanceReported(accountId, deposit, period.balance(),
					context.enforces(BALANCE_WITHIN_GUARANTEE, EventId.of(deposit), "customer told the balance is " + period.balance())),
				BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId),
				"excess-balance-reported/" + deposit);
	}
}
