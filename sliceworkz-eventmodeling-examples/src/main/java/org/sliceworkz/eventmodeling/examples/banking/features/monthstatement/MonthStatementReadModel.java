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
package org.sliceworkz.eventmodeling.examples.banking.features.monthstatement;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.domain.DomainConceptTag;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Live read model for a specific month's bank statement.
 * <p>
 * Queried via: {@code bc.read(MonthStatementReadModel.class, accountId, month)}
 * <p>
 * <b>This is where "Closing The Books" pays off for read performance.</b>
 * Unlike the {@code CurrentPeriodReadModel} which replays ALL account events,
 * this read model filters by BOTH account AND month tags. It only replays
 * events for the specific month requested — typically a very small number.
 * <p>
 * For a closed month this is effectively an immutable projection (no new events
 * will ever be added), making it a perfect candidate for caching.
 */
public class MonthStatementReadModel implements ReadModel<BankingEvent> {

	private final DomainConceptId accountId;
	private final YearMonth month;

	private BigDecimal openingBalance;
	private BigDecimal closingBalance;
	private final List<TransactionLine> transactions = new ArrayList<>();
	private boolean closed;

	public MonthStatementReadModel(DomainConceptId accountId, YearMonth month) {
		this.accountId = accountId;
		this.month = month;
	}

	/**
	 * Filter by BOTH account AND month tags.
	 * This means only events for this specific period are replayed.
	 * For a closed month, this is a small, bounded set of events.
	 */
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
				// Only seen if this is the first month (opening month matches)
				openingBalance = BigDecimal.ZERO;
			}
			case MonthOpened mo -> {
				// Carry-forward opening balance for this period
				openingBalance = mo.carryForwardBalance();
			}
			case MoneyDeposited d -> {
				transactions.add(new TransactionLine(
					"DEPOSIT", d.amount(), d.description()));
			}
			case MoneyWithdrawn w -> {
				transactions.add(new TransactionLine(
					"WITHDRAWAL", w.amount().negate(), w.description()));
			}
			case MonthClosed mc -> {
				closingBalance = mc.closingBalance();
				closed = true;
			}
		}
	}

	// ── Query results ────────────────────────────────────────────────────

	public Optional<MonthStatement> getStatement() {
		if (openingBalance == null && transactions.isEmpty()) return Optional.empty();

		BigDecimal runningBalance = openingBalance != null ? openingBalance : BigDecimal.ZERO;
		for (var tx : transactions) {
			runningBalance = runningBalance.add(tx.amount());
		}

		return Optional.of(new MonthStatement(
			accountId.value(), month, openingBalance,
			closed ? closingBalance : runningBalance,
			List.copyOf(transactions), closed
		));
	}

	public record MonthStatement(
		String accountId,
		YearMonth month,
		BigDecimal openingBalance,
		BigDecimal currentBalance,
		List<TransactionLine> transactions,
		boolean closed
	) {}

	public record TransactionLine(
		String type,
		BigDecimal amount,
		String description
	) {}
}
