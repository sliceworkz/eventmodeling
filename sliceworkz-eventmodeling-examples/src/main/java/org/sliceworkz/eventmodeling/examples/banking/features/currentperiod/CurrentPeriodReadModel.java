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
package org.sliceworkz.eventmodeling.examples.banking.features.currentperiod;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.*;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Live read model that shows the state of the current account period.
 * <p>
 * Queried via: {@code bc.read(CurrentPeriodReadModel.class, accountId)}
 * <p>
 * Uses the savepoint pattern via {@link #initQuery()}: a single backwards
 * query finds the most recent period-opening event ({@code MonthOpened} or
 * {@code AccountOpened}), which provides the carry-forward balance and
 * identifies the active month. The main {@link #eventQuery()} then only
 * processes movement events after that savepoint.
 */
public class CurrentPeriodReadModel implements ReadModel<BankingEvent> {

	private final AccountId accountId;

	private YearMonth activeMonth;
	private BigDecimal balance = BigDecimal.ZERO;
	private BigDecimal periodDeposits = BigDecimal.ZERO;
	private BigDecimal periodWithdrawals = BigDecimal.ZERO;
	private int periodTransactionCount;
	private boolean periodClosed;

	public CurrentPeriodReadModel(AccountId accountId) {
		this.accountId = accountId;
	}

	@Override
	public EventQuery initQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.of(AccountOpened.class, MonthOpened.class),
			BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId)
		).backwards().limit(1);
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.of(MoneyDeposited.class, MoneyWithdrawn.class, MonthClosed.class),
			BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId)
		);
	}

	@Override
	public void when(BankingEvent event) {
		switch (event) {
			case AccountOpened ao -> {
				activeMonth = ao.initialMonth();
				balance = BigDecimal.ZERO;
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
				// ── This is the "carry forward" in action ──
				// Reset period counters, start fresh with carried balance
				activeMonth = mo.month();
				balance = mo.carryForwardBalance();
				periodDeposits = BigDecimal.ZERO;
				periodWithdrawals = BigDecimal.ZERO;
				periodTransactionCount = 0;
				periodClosed = false;
			}
		}
	}

	// ── Query results ────────────────────────────────────────────────────

	public Optional<CurrentPeriod> getCurrentPeriod() {
		if (activeMonth == null) return Optional.empty();
		return Optional.of(new CurrentPeriod(
			accountId.value(), activeMonth, balance,
			periodDeposits, periodWithdrawals, periodTransactionCount, periodClosed
		));
	}

	public record CurrentPeriod(
		String accountId,
		YearMonth month,
		BigDecimal balance,
		BigDecimal deposits,
		BigDecimal withdrawals,
		int transactionCount,
		boolean closed
	) {}
}
