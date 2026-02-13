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
package org.sliceworkz.eventmodeling.examples.banking.features.currentperiod;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Decision model that tracks the state of a specific account period.
 * <p>
 * Used by commands (Deposit, Withdraw, CloseMonth) to:
 * <ul>
 *   <li>Know the current balance</li>
 *   <li>Know which month is currently active</li>
 *   <li>Know if the period is already closed</li>
 *   <li>Get running totals for the close summary</li>
 * </ul>
 *
 * <p><b>Always requires a month parameter.</b> The caller is expected to look
 * up the active month first via {@link ActiveMonthReadModel} (a single
 * backwards query) and pass it in. This ensures only events for that specific
 * period are replayed — never the full account history.</p>
 *
 * <p>The query leverages the tag rotation built into the "Closing The Books"
 * pattern: each period's events are tagged with both the account and the month,
 * so filtering by both tags yields only the events for that period. The
 * {@code MonthOpened} carry-forward event (or {@code AccountOpened} for the
 * first period) provides the opening balance, so no prior history is needed.</p>
 *
 * <p>The query filter is also used for DCB optimistic locking, ensuring
 * concurrent changes within the period are detected.</p>
 */
public class ActivePeriodDecisionModel implements DecisionModel<BankingEvent> {

	private final DomainConceptId accountId;
	private final YearMonth month;

	// Current state
	private boolean accountExists;
	private YearMonth activeMonth;
	private BigDecimal balance = BigDecimal.ZERO;
	private BigDecimal periodDeposits = BigDecimal.ZERO;
	private BigDecimal periodWithdrawals = BigDecimal.ZERO;
	private int periodTransactionCount;
	private BigDecimal periodOpeningBalance = BigDecimal.ZERO;
	private boolean periodClosed;

	/**
	 * Creates a decision model scoped to a specific month.
	 * Only events tagged with the given account + month are replayed.
	 * <p>
	 * Use {@link ActiveMonthReadModel} to look up the active month first.
	 *
	 * @param accountId the account to query
	 * @param month the month to scope the query to (must not be null)
	 */
	public ActivePeriodDecisionModel(DomainConceptId accountId, YearMonth month) {
		this.accountId = accountId;
		this.month = month;
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.any(),
			Tags.of(
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId),
				DomainConceptTag.of(BankingDomainWithClosingTheBooks.CONCEPT_MONTH,
					new DomainConceptId(month.toString()))
			)
		);
	}

	@Override
	public void when(Event<BankingEvent> eventWithMetaData) {
		switch (eventWithMetaData.data()) {
			case AccountOpened ao -> {
				accountExists = true;
				activeMonth = ao.initialMonth();
				balance = BigDecimal.ZERO;
				periodOpeningBalance = BigDecimal.ZERO;
				periodClosed = false;
			}
			case MoneyDeposited d -> {
				balance = balance.add(d.amount());
				periodDeposits = periodDeposits.add(d.amount());
				periodTransactionCount++;
			}
			case MoneyWithdrawn w -> {
				balance = balance.subtract(w.amount());
				periodWithdrawals = periodWithdrawals.add(w.amount());
				periodTransactionCount++;
			}
			case MonthClosed mc -> {
				periodClosed = true;
			}
			case MonthOpened mo -> {
				// This is the carry-forward: reset period counters, keep the balance
				accountExists = true;
				activeMonth = mo.month();
				balance = mo.carryForwardBalance();
				periodOpeningBalance = mo.carryForwardBalance();
				periodDeposits = BigDecimal.ZERO;
				periodWithdrawals = BigDecimal.ZERO;
				periodTransactionCount = 0;
				periodClosed = false;
			}
		}
	}

	// ── Accessors used by commands ───────────────────────────────────────

	public boolean accountExists() { return accountExists; }
	public YearMonth activeMonth() { return activeMonth; }
	public BigDecimal balance() { return balance; }
	public BigDecimal periodDeposits() { return periodDeposits; }
	public BigDecimal periodWithdrawals() { return periodWithdrawals; }
	public int periodTransactionCount() { return periodTransactionCount; }
	public BigDecimal periodOpeningBalance() { return periodOpeningBalance; }
	public boolean isPeriodClosed() { return periodClosed; }
}
