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
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.*;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Decision model that tracks the current state of an account's active period.
 * <p>
 * Used by commands (Deposit, Withdraw, CloseMonth) to:
 * <ul>
 *   <li>Know the current balance</li>
 *   <li>Know which month is currently active</li>
 *   <li>Know if the period is already closed</li>
 *   <li>Get running totals for the close summary</li>
 * </ul>
 *
 * <p><b>Key design choice:</b> This queries by account tag only (not month tag),
 * so it sees ALL events for the account across all periods. It then tracks
 * state by processing the full lifecycle. This is where snapshots or
 * "closing the books" helps — once a month is closed, the MonthOpened event
 * carries forward just the balance, so the decision model only needs to
 * replay from the most recent MonthOpened (or AccountOpened for the first month).</p>
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

	public ActivePeriodDecisionModel(DomainConceptId accountId) {
		this.accountId = accountId;
	}

	/**
	 * Query ALL events for this account (across all months).
	 * The tag filter is on account identity only — no month filter.
	 */
	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(
			org.sliceworkz.eventstore.query.EventTypesFilter.any(),
			DomainConceptTags.of(BankingDomainWithClosingTheBooks.CONCEPT_ACCOUNT, accountId)
		);
	}

	@Override
	public void when(BankingEvent event) {
		switch (event) {
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
