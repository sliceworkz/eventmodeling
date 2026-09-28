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

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BALANCE_WITHIN_GUARANTEE;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.GUARANTEED_BALANCE;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ORIGIN_OF_FUNDS_AMOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ORIGIN_OF_FUNDS_EXPLAINED;

import java.math.BigDecimal;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.Overriding;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Deposits money into the account's currently active period.
 * <p>
 * Uses the {@link ActivePeriodDecisionModel} to determine whether the account exists, which month is
 * currently active (discovered via initQuery) and whether that period is still open — the requests that make
 * no sense, rejected with a {@link BusinessException}.
 * <p>
 * It checks two of the bank's behavioral rules, at the two enforcement levels the withdrawal does not use:
 * <ul>
 *   <li>{@code origin-of-funds-explained} — <b>override with explanation</b>: a deposit above 10.000 goes
 *       through once the teller explains where the money comes from. Anyone may override it; the explanation
 *       is what the override costs, and it is recorded in the {@code MoneyDeposited} event. The command does
 *       not call {@code overridableWhen}, and so leaves it open to every teller.</li>
 *   <li>{@code balance-within-guarantee} — <b>deferred enforcement</b>: the deposit is never refused for it.
 *       The violation is recorded on the event and tagged {@code x-rule-deferred}, and
 *       {@code ReportExcessBalanceAutomation} enforces it afterwards by reporting the excess to the customer.</li>
 * </ul>
 *
 * <p>The raised event is tagged with both account AND month, so it will be
 * filtered correctly when loading a specific period's events.</p>
 *
 * @param accountId the account to deposit into
 * @param amount the amount
 * @param description what the deposit is
 * @param overrides the overrides the teller asked for — with the explanation of the origin of the funds
 */
public record DepositCommand ( AccountId accountId, BigDecimal amount, String description, Overrides overrides )
		implements Command<BankingEvent>, Overriding {

	public DepositCommand {
		if ( accountId == null || amount == null || amount.signum() <= 0 ) {
			throw new IllegalArgumentException("a deposit needs an account and a positive amount");
		}
		overrides = ( overrides == null ) ? Overrides.none() : overrides;
	}

	/** A deposit overriding nothing. */
	public DepositCommand ( AccountId accountId, BigDecimal amount, String description ) {
		this(accountId, amount, description, Overrides.none());
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {

		var period = new ActivePeriodDecisionModel(accountId);
		var result = context.decisionModels(period);

		BusinessException.when(!period.accountExists(), "Account does not exist");
		BusinessException.when(period.isPeriodClosed(),
			"Period " + period.activeMonth() + " is closed, cannot deposit");

		BigDecimal balanceAfter = period.balance().add(amount);

		context.check(ORIGIN_OF_FUNDS_EXPLAINED, amount.compareTo(ORIGIN_OF_FUNDS_AMOUNT) > 0,
			"A deposit of " + amount + " is above " + ORIGIN_OF_FUNDS_AMOUNT + ": explain where the money comes from");

		context.check(BALANCE_WITHIN_GUARANTEE, balanceAfter.compareTo(GUARANTEED_BALANCE) > 0,
			"The balance would become " + balanceAfter + ", above the guaranteed " + GUARANTEED_BALANCE + "; the customer will be informed");

		// Tag with both account identity AND period identity
		result.raiseEvent(
			new MoneyDeposited(accountId, period.activeMonth(), amount, description, context.ruleViolations()),
			Tags.of(
				BankingDomainWithClosingTheBooks.ACCOUNT.tag(accountId),
				BankingDomainWithClosingTheBooks.MONTH.tag(BankingDomainWithClosingTheBooks.monthId(period.activeMonth()))
			)
		);
	}
}
