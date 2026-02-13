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
package org.sliceworkz.eventmodeling.examples.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.sliceworkz.eventmodeling.domain.DomainConcept;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;

/**
 * Extended banking domain showing the "Closing The Books" pattern applied to
 * monthly bank account statement periods.
 *
 * <h2>How it works</h2>
 * <p>
 * Each bank account has a lifecycle of monthly periods. When an account is opened,
 * the first month is implicitly started. Deposits and withdrawals happen within the
 * current active period. At the end of the month, the books are "closed": a
 * {@code MonthClosed} summary event captures the final balance, transaction count,
 * and totals. An {@code MonthOpened} event starts the next period, carrying the
 * closing balance forward as the opening balance.
 * </p>
 *
 * <h2>Tag structure (identity rotation)</h2>
 * <p>
 * The key insight is that the "period" tag rotates:
 * </p>
 * <ul>
 *   <li>{@code account=acc-123, month=2025-01} — events for January 2025</li>
 *   <li>{@code account=acc-123, month=2025-02} — events for February 2025</li>
 *   <li>{@code account=acc-123} (entity tags only) — queries across ALL months</li>
 * </ul>
 *
 * <h2>Event Modeling patterns used</h2>
 * <ul>
 *   <li><b>STATE_CHANGE</b>: DepositCommand, WithdrawCommand, CloseMonthCommand</li>
 *   <li><b>STATE_READ</b>: CurrentPeriodReadModel (live), MonthStatementReadModel (live)</li>
 *   <li><b>AUTOMATION</b>: MonthEndClosingAutomation (time-triggered)</li>
 * </ul>
 */
public interface BankingDomainWithClosingTheBooks {

	// ── Domain Concepts ──────────────────────────────────────────────────

	DomainConcept CONCEPT_ACCOUNT = DomainConcept.of("account");
	DomainConcept CONCEPT_MONTH = DomainConcept.of("month");

	// ── Domain Events ────────────────────────────────────────────────────

	/**
	 * All domain events for the banking bounded context.
	 * <p>
	 * Notice how the events form a natural lifecycle:
	 * AccountOpened → (MoneyDeposited | MoneyWithdrawn)* → MonthClosed → MonthOpened → ...
	 */
	sealed interface BankingEvent {

		/**
		 * An account was opened. This is also the implicit start of the first period.
		 */
		record AccountOpened(
			DomainConceptId accountId,
			DomainConceptId customerId,
			YearMonth initialMonth,
			LocalDate date
		) implements BankingEvent {}

		/**
		 * Money was deposited into the account during the current period.
		 */
		record MoneyDeposited(
			DomainConceptId accountId,
			YearMonth month,
			BigDecimal amount,
			String description
		) implements BankingEvent {}

		/**
		 * Money was withdrawn from the account during the current period.
		 */
		record MoneyWithdrawn(
			DomainConceptId accountId,
			YearMonth month,
			BigDecimal amount,
			String description
		) implements BankingEvent {}

		// ── Closing The Books events ─────────────────────────────────

		/**
		 * The summary event that "closes the books" for a month.
		 * Contains everything needed for the closed period's statement
		 * AND the carry-forward data for the next period.
		 *
		 * <p>This is NOT a snapshot — it's a first-class domain event that
		 * represents the business operation of closing the monthly books.</p>
		 */
		record MonthClosed(
			DomainConceptId accountId,
			YearMonth month,
			BigDecimal openingBalance,
			BigDecimal closingBalance,
			BigDecimal totalDeposits,
			BigDecimal totalWithdrawals,
			int transactionCount,
			LocalDate closedOn
		) implements BankingEvent {}

		/**
		 * A new month was opened, carrying forward the balance from the previous period.
		 * This is the "seed" event for the new period's stream.
		 */
		record MonthOpened(
			DomainConceptId accountId,
			YearMonth month,
			BigDecimal carryForwardBalance,
			YearMonth previousMonth
		) implements BankingEvent {}
	}

	// ── Inbound Events (external triggers) ───────────────────────────────

	sealed interface BankingInboundEvent {

		/**
		 * A time-based trigger indicating that a specific month has ended.
		 * This could come from a scheduler/cron job.
		 */
		record MonthEndReached(YearMonth month) implements BankingInboundEvent {}
	}

	// ── Outbound Events ──────────────────────────────────────────────────

	sealed interface BankingOutboundEvent {

		record MonthlyStatementReady(
			DomainConceptId accountId,
			YearMonth month
		) implements BankingOutboundEvent {}
	}
}
