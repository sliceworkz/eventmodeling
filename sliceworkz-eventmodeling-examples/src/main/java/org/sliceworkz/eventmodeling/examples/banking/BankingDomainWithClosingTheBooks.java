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
package org.sliceworkz.eventmodeling.examples.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.RuleFollowUp;
import org.sliceworkz.eventmodeling.rules.RuleViolation;


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
 *   <li><b>AUTOMATION</b>: MonthEndClosingAutomation (time-triggered), ReportExcessBalanceAutomation
 *       (the follow-up of a deferred enforcement), and BlockCardsOfAccountPolicy — the other form of an
 *       automation: whenever an account is frozen or closed, block its cards</li>
 * </ul>
 *
 * <h2>The rulebook</h2>
 * <p>
 * The {@link BusinessRule business rules} below are part of the domain's vocabulary as much as its events
 * are: the statements are what a teller is shown, the ids are what history records. Between them, the
 * withdrawal and the deposit use every SBVR enforcement level the framework knows, so this is the place to
 * look for how each one is written. What does <em>not</em> appear here is a request that makes no sense —
 * an account that does not exist, a period already closed: those are {@code BusinessException}s in the
 * commands, not rules with an enforcement level.
 * </p>
 * <table>
 * <caption>Banking rules</caption>
 * <tr><th>Rule</th><th>Level</th><th>Checked by</th><th>Follow-up</th></tr>
 * <tr><td>{@link #MAXIMUM_WITHDRAWAL}</td><td>strictly enforced</td><td>WithdrawCommand</td><td>—</td></tr>
 * <tr><td>{@link #NO_OVERDRAFT}</td><td>pre-authorized override</td><td>WithdrawCommand, which grants it to
 *     identified tellers up to three times a day</td><td>—</td></tr>
 * <tr><td>{@link #LARGE_WITHDRAWAL_JUSTIFIED}</td><td>post-justified override</td><td>WithdrawCommand</td>
 *     <td>JustifyWithdrawalCommand, OverridesAwaitingJustificationReadModel</td></tr>
 * <tr><td>{@link #WITHDRAWAL_DESCRIBED}</td><td>guideline</td><td>WithdrawCommand</td><td>—</td></tr>
 * <tr><td>{@link #ORIGIN_OF_FUNDS_EXPLAINED}</td><td>override with explanation</td><td>DepositCommand</td><td>—</td></tr>
 * <tr><td>{@link #BALANCE_WITHIN_GUARANTEE}</td><td>deferred enforcement</td><td>DepositCommand</td>
 *     <td>ReportExcessBalanceAutomation</td></tr>
 * </table>
 */
public interface BankingDomainWithClosingTheBooks {

	// ── Entities ─────────────────────────────────────────────────────────

	record AccountId ( String value ) implements EntityId { }
	record CustomerId ( String value ) implements EntityId { }
	/** A month is an entity here: the period being closed has an identity of its own, {@code 2025-01}, and events are tagged with it. */
	record MonthId ( String value ) implements EntityId { }
	/** A payment card issued on an account; blocked as a whole when its account is frozen or closed. */
	record CardId ( String value ) implements EntityId { }

	Entity<AccountId> ACCOUNT = Entity.of("account", AccountId::new);
	Entity<CustomerId> CUSTOMER = Entity.of("customer", CustomerId::new);
	Entity<MonthId> MONTH = Entity.of("month", MonthId::new);
	Entity<CardId> CARD = Entity.of("card", CardId::new);

	/** The id of a month, so that every tag on a month spells it the same way: {@link YearMonth#toString()}. */
	static MonthId monthId ( YearMonth month ) {
		return MONTH.id(month.toString());
	}

	// ── Business rules ───────────────────────────────────────────────────
	//
	// The ids are wire format (recorded in every RuleViolation, used as tag values, sent back by front
	// ends); the statements are wording and may change; the enforcement level is policy and may change too
	// — a bank tightening its overdraft policy changes one word below and nothing else.

	/** Above this amount a single withdrawal is refused, whoever asks. */
	BigDecimal MAXIMUM_WITHDRAWAL_AMOUNT = new BigDecimal("50000");

	/** Above this amount a withdrawal is an exception that has to be justified afterwards. */
	BigDecimal LARGE_WITHDRAWAL_AMOUNT = new BigDecimal("5000");

	/** Above this amount a deposit has to come with an explanation of where the money comes from. */
	BigDecimal ORIGIN_OF_FUNDS_AMOUNT = new BigDecimal("10000");

	/** The balance a deposit guarantee scheme covers. */
	BigDecimal GUARANTEED_BALANCE = new BigDecimal("100000");

	/** How many overdraft exceptions one teller may grant per day. */
	int DAILY_OVERDRAFT_EXCEPTIONS = 3;

	/**
	 * Strictly enforced: nobody may override it. Not a {@code BusinessException} all the same, because it is a
	 * behavioral rule of the bank — reported next to every other violation when a withdrawal is evaluated, and
	 * relaxable one day by changing its level rather than the command.
	 */
	BusinessRule MAXIMUM_WITHDRAWAL = BusinessRule.of("maximum-withdrawal",
			"A single withdrawal must not exceed " + MAXIMUM_WITHDRAWAL_AMOUNT + ".")
		.enforcedAt(EnforcementLevel.STRICTLY_ENFORCED);

	/**
	 * Pre-authorized override: only a teller the command authorizes may let an account go negative — an
	 * identified one, and no more than {@link #DAILY_OVERDRAFT_EXCEPTIONS} times a day. That decision is taken
	 * in {@code WithdrawCommand}, on a decision model counting the teller's earlier overrides.
	 */
	BusinessRule NO_OVERDRAFT = BusinessRule.of("no-overdraft",
			"A withdrawal must not make the balance of the account negative.")
		.enforcedAt(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE);

	/**
	 * Post-justified override: a teller may pay out a large amount on the spot, and has to justify it
	 * afterwards. Until then the withdrawal is listed by {@code OverridesAwaitingJustificationReadModel}.
	 */
	BusinessRule LARGE_WITHDRAWAL_JUSTIFIED = BusinessRule.of("large-withdrawal-justified",
			"A withdrawal above " + LARGE_WITHDRAWAL_AMOUNT + " must be justified.")
		.enforcedAt(EnforcementLevel.POST_JUSTIFIED_OVERRIDE);

	/** Guideline: nothing stops a withdrawal without a description, but the teller is told it should have one. */
	BusinessRule WITHDRAWAL_DESCRIBED = BusinessRule.of("withdrawal-described",
			"A withdrawal should say what it is for.")
		.enforcedAt(EnforcementLevel.GUIDELINE);

	/** Override with explanation: a large deposit goes through once the teller records where the money comes from. */
	BusinessRule ORIGIN_OF_FUNDS_EXPLAINED = BusinessRule.of("origin-of-funds-explained",
			"A deposit above " + ORIGIN_OF_FUNDS_AMOUNT + " must come with an explanation of the origin of the funds.")
		.enforcedAt(EnforcementLevel.OVERRIDE_WITH_EXPLANATION);

	/**
	 * Deferred enforcement: a deposit is never refused for it, and an account above the guarantee is reported to
	 * its customer afterwards, by {@code ReportExcessBalanceAutomation}.
	 */
	BusinessRule BALANCE_WITHIN_GUARANTEE = BusinessRule.of("balance-within-guarantee",
			"The balance of an account should not exceed the guaranteed " + GUARANTEED_BALANCE + ".")
		.enforcedAt(EnforcementLevel.DEFERRED_ENFORCEMENT);

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
			AccountId accountId,
			CustomerId customerId,
			YearMonth initialMonth,
			LocalDate date
		) implements BankingEvent {}

		/**
		 * Money was deposited into the account during the current period.
		 *
		 * @param ruleViolations the business rules the deposit went ahead in violation of — an explained
		 *        origin of funds, a balance above the guarantee — as the command recorded them; empty for an
		 *        ordinary deposit, and for deposits stored before the field existed
		 */
		record MoneyDeposited(
			AccountId accountId,
			YearMonth month,
			BigDecimal amount,
			String description,
			List<RuleViolation> ruleViolations
		) implements BankingEvent {

			// lenient, as every payload record must be: this is what Jackson calls on every read of history
			public MoneyDeposited {
				ruleViolations = ( ruleViolations == null ) ? List.of() : List.copyOf(ruleViolations);
			}

			/** A deposit that violated no rule. */
			public MoneyDeposited ( AccountId accountId, YearMonth month, BigDecimal amount, String description ) {
				this(accountId, month, amount, description, List.of());
			}
		}

		/**
		 * Money was withdrawn from the account during the current period.
		 *
		 * @param ruleViolations the business rules the withdrawal went ahead in violation of — an overdraft
		 *        a teller was authorized to grant, a large amount still to be justified, a missing
		 *        description — as the command recorded them; empty for an ordinary withdrawal, and for
		 *        withdrawals stored before the field existed
		 */
		record MoneyWithdrawn(
			AccountId accountId,
			YearMonth month,
			BigDecimal amount,
			String description,
			List<RuleViolation> ruleViolations
		) implements BankingEvent {

			public MoneyWithdrawn {
				ruleViolations = ( ruleViolations == null ) ? List.of() : List.copyOf(ruleViolations);
			}

			/** A withdrawal that violated no rule. */
			public MoneyWithdrawn ( AccountId accountId, YearMonth month, BigDecimal amount, String description ) {
				this(accountId, month, amount, description, List.of());
			}
		}

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
			AccountId accountId,
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
			AccountId accountId,
			YearMonth month,
			BigDecimal carryForwardBalance,
			YearMonth previousMonth
		) implements BankingEvent {}

		// ── Rulebook follow-ups ──────────────────────────────────────

		/**
		 * The facts that finish what a violation left open: a justification given afterwards for a
		 * post-justified override, an enforcement carried out for a deferred one. They move no money, which
		 * is why the read models folding balances and periods ignore the whole branch in one case.
		 */
		sealed interface RulebookFollowUp extends BankingEvent {

			/** @return the account the follow-up concerns */
			AccountId accountId();

			/**
			 * A teller justified, afterwards, a withdrawal that went ahead under a post-justified override.
			 *
			 * @param withdrawal the id of the {@code MoneyWithdrawn} event being justified
			 * @param justification the justification as the kernel links it — the rule, the withdrawal and the
			 *                      teller's words — from {@code CommandContext.justifies(...)}
			 */
			record WithdrawalJustified(
				AccountId accountId,
				String withdrawal,
				RuleFollowUp justification
			) implements RulebookFollowUp {}

			/**
			 * The customer was told the balance of the account exceeds what the deposit guarantee covers: the
			 * enforcement of {@code balance-within-guarantee}, deferred when the deposit was made.
			 *
			 * @param deposit the id of the {@code MoneyDeposited} event that took the balance over the guarantee
			 * @param balance the balance right after that deposit
			 * @param enforcement the enforcement as the kernel links it, from {@code CommandContext.enforces(...)}
			 */
			record ExcessBalanceReported(
				AccountId accountId,
				String deposit,
				BigDecimal balance,
				RuleFollowUp enforcement
			) implements RulebookFollowUp {}
		}

		// ── Account access ───────────────────────────────────────────

		/**
		 * Who and what may still use an account: its cards, and whether it was frozen or closed. Like the
		 * rulebook follow-ups these move no money, which is why the read models folding balances and periods
		 * ignore the whole branch in one case.
		 * <p>
		 * {@code AccountFrozen} and {@code AccountClosed} are two variants of one fact as far as the cards are
		 * concerned — the account may no longer be paid from — and {@code BlockCardsOfAccountPolicy} reacts to
		 * both with the same command.
		 */
		sealed interface AccountAccess extends BankingEvent {

			/** @return the account the fact concerns */
			AccountId accountId();

			/** A card was issued on the account. Tagged with the account and the card. */
			record CardIssued(
				AccountId accountId,
				CardId cardId,
				LocalDate issuedOn
			) implements AccountAccess {}

			/** The account was frozen: no new cards, and the cards it has are blocked. */
			record AccountFrozen(
				AccountId accountId,
				String reason
			) implements AccountAccess {}

			/** The account was closed: no new cards, and the cards it has are blocked. */
			record AccountClosed(
				AccountId accountId,
				LocalDate closedOn
			) implements AccountAccess {}

			/** A card was blocked, and can no longer be used. Tagged with the account and the card. */
			record CardBlocked(
				AccountId accountId,
				CardId cardId,
				String reason
			) implements AccountAccess {}
		}
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
			AccountId accountId,
			YearMonth month
		) implements BankingOutboundEvent {}
	}
}
