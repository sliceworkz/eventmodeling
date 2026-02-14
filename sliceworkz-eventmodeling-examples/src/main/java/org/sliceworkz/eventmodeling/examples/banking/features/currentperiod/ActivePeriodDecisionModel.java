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
import org.sliceworkz.eventmodeling.domain.DomainConceptTags;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;
import org.sliceworkz.eventstore.events.Event;
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
 * <p>Uses the savepoint pattern via {@link #initQuery()}: a single backwards
 * query finds the most recent period-opening event ({@code MonthOpened} or
 * {@code AccountOpened}), which provides the carry-forward balance and
 * identifies the active month. The main {@link #eventQuery()} then only
 * processes movement events (deposits, withdrawals, month closings) after
 * that savepoint — never the full account history.</p>
 *
 * <p>The {@link #eventQuery()} filter is used for DCB optimistic locking,
 * ensuring concurrent changes within the period are detected.</p>
 */
public class ActivePeriodDecisionModel implements DecisionModel<BankingEvent> {

	private final DomainConceptId accountId;

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
	 * Creates a decision model for the given account.
	 * The active month is discovered automatically via {@link #initQuery()}.
	 *
	 * @param accountId the account to query
	 */
	public ActivePeriodDecisionModel(DomainConceptId accountId) {
		this.accountId = accountId;
	}

	@Override
	public EventQuery initQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.of(AccountOpened.class, MonthOpened.class),
			DomainConceptTags.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId)
		).backwards().limit(1);
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.of(MoneyDeposited.class, MoneyWithdrawn.class, MonthClosed.class),
			DomainConceptTags.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId)
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
