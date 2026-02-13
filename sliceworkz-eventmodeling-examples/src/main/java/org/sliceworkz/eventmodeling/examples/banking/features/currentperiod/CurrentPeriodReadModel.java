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
import java.util.Optional;

import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.*;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Live read model that shows the state of a specific account period.
 * <p>
 * Queried via: {@code bc.read(CurrentPeriodReadModel.class, accountId, month)}
 * <p>
 * The caller must first look up the active month via {@link ActiveMonthReadModel}
 * and pass it in. This ensures the read model only replays events for that specific
 * period — never the full account history.
 * <p>
 * The query leverages tag rotation: each period's events are tagged with both account
 * and month, so filtering by both tags yields only the bounded set of events for that
 * period. The {@code MonthOpened} carry-forward event (or {@code AccountOpened} for the
 * first period) provides the opening balance.
 */
public class CurrentPeriodReadModel implements ReadModel<BankingEvent> {

	private final DomainConceptId accountId;
	private final YearMonth month;

	private YearMonth activeMonth;
	private BigDecimal balance = BigDecimal.ZERO;
	private BigDecimal periodDeposits = BigDecimal.ZERO;
	private BigDecimal periodWithdrawals = BigDecimal.ZERO;
	private int periodTransactionCount;
	private boolean periodClosed;

	public CurrentPeriodReadModel(DomainConceptId accountId, YearMonth month) {
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
