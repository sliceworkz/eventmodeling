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
package org.sliceworkz.eventmodeling.examples.banking.features.closemonth;

import java.time.LocalDate;
import java.time.YearMonth;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Closes the current month's books and opens the next month.
 * <p>
 * This is the core "Closing The Books" command. It:
 * <ol>
 *   <li>Reads the current period state via {@link ActivePeriodDecisionModel}</li>
 *   <li>Validates the period is still open and matches the requested month</li>
 *   <li>Raises a {@code MonthClosed} summary event (tagged to the current month)</li>
 *   <li>Raises a {@code MonthOpened} event (tagged to the NEXT month) carrying the balance forward</li>
 * </ol>
 *
 * <p><b>Important design detail:</b> The two events are raised in a single command execution,
 * so they are appended atomically. The MonthClosed event is tagged with the OLD month,
 * and the MonthOpened event is tagged with the NEW month. Both are tagged with the
 * account identity so they show up when querying all events for an account.
 * Idempotency is ensured by the {@link ActivePeriodDecisionModel} — if the period
 * is already closed, the command rejects the operation.</p>
 *
 * <p>This command can be invoked manually (user action) or by an automation
 * (scheduled month-end processing).</p>
 */
public class CloseMonthCommand implements Command<BankingEvent> {

	private final DomainConceptId accountId;
	private final YearMonth monthToClose;

	public CloseMonthCommand(DomainConceptId accountId, YearMonth monthToClose) {
		this.accountId = accountId;
		this.monthToClose = monthToClose;
	}

	@Override
	public void execute(CommandContext<BankingEvent, BankingEvent> context) {

		var period = new ActivePeriodDecisionModel(accountId);
		var result = context.decisionModels(period);

		// ── Validation ───────────────────────────────────────────────

		if (!period.accountExists()) {
			throw new IllegalStateException("Account does not exist");
		}
		if (period.isPeriodClosed()) {
			throw new IllegalStateException(
				"Period " + period.activeMonth() + " is already closed");
		}
		if (!period.activeMonth().equals(monthToClose)) {
			throw new IllegalStateException(
				"Requested to close " + monthToClose
				+ " but active period is " + period.activeMonth());
		}

		// ── Close the current month ──────────────────────────────────

		Tags currentMonthTags = Tags.of(
			DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId),
			DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_MONTH,
				DomainConceptId.of(monthToClose.toString()))
		);

		result.raiseEvent(
			new MonthClosed(
				accountId,
				monthToClose,
				period.periodOpeningBalance(),
				period.balance(),
				period.periodDeposits(),
				period.periodWithdrawals(),
				period.periodTransactionCount(),
				LocalDate.now()
			),
			currentMonthTags
		);

		// ── Open the next month with carry-forward ───────────────────

		YearMonth nextMonth = monthToClose.plusMonths(1);

		Tags nextMonthTags = Tags.of(
			DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId),
			DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_MONTH,
				DomainConceptId.of(nextMonth.toString()))
		);

		result.raiseEvent(
			new MonthOpened(
				accountId,
				nextMonth,
				period.balance(),       // carry forward the closing balance
				monthToClose
			),
			nextMonthTags
		);
	}
}
