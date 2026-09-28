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
package org.sliceworkz.eventmodeling.examples.banking.features.withdraw;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_AMOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.MAXIMUM_WITHDRAWAL;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.MAXIMUM_WITHDRAWAL_AMOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.NO_OVERDRAFT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.WITHDRAWAL_DESCRIBED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActivePeriodDecisionModel;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.Overriding;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Withdraws money from the account's currently active period — and the worked example of a command that
 * checks business rules at several enforcement levels next to requests that simply make no sense.
 *
 * <h2>Two kinds of "no"</h2>
 * <ul>
 *   <li><b>The request makes no sense</b>: the account does not exist, or its period is closed. No override
 *       can make a withdrawal from a non-existing account meaningful, so these are
 *       {@link BusinessException}s, thrown on the spot — SBVR's definitional rules.</li>
 *   <li><b>The bank's rules</b>, behavioral in SBVR's terms: people can break them, and each has an
 *       {@linkplain org.sliceworkz.eventmodeling.rules.EnforcementLevel enforcement level} saying what
 *       happens when they do. They are {@linkplain CommandContext#check checked}, never thrown, and the
 *       kernel judges them all once the command has run:
 *       <ul>
 *         <li>{@code maximum-withdrawal} — strictly enforced: blocks, whoever asks</li>
 *         <li>{@code no-overdraft} — pre-authorized override: only a teller this command authorizes</li>
 *         <li>{@code large-withdrawal-justified} — post-justified override: goes through, to be justified
 *             later with {@code JustifyWithdrawalCommand}</li>
 *         <li>{@code withdrawal-described} — guideline: advice, never blocks</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Who may override: the command decides, on history</h2>
 * The overdraft is the rule to read closely. Whether <em>this</em> teller may grant it is decided right here,
 * by {@link OverdraftExceptionsTodayDecisionModel}: identified tellers only, and at most three exceptions a
 * day each. Because that model is one of the command's decision models, its query is part of the consistency
 * boundary — two withdrawals racing past the quota cannot both succeed; the second one conflicts, and its
 * retry counts the first. A policy configured beside the bounded context could not promise that.
 *
 * <h2>Raise as if everything were allowed</h2>
 * The command never branches on the outcome of a check: it raises its event with
 * {@link CommandContext#ruleViolations()} in the payload, and if a violation stops the execution the kernel
 * rejects it before anything is appended. What the event ends up carrying is exactly the violations the
 * withdrawal went ahead with — the overdraft a teller granted, the justification still owed, the missing
 * description — and the kernel tags it for each of them, which is what the quota above counts.
 *
 * <h2>Inputs</h2>
 * {@code today} is an input like the amount, because a command that needs "now" takes it rather than reading
 * a clock: the quota is per day, and a test pins which day. {@code overrides} are the overrides the teller
 * ticked, sent along by the front end — see {@link Overrides}.
 *
 * @param accountId the account to withdraw from
 * @param amount the amount
 * @param description what the withdrawal is for; a blank one is allowed and advised against
 * @param today the day the withdrawal is made, which the overdraft quota counts in
 * @param overrides the overrides the teller asked for
 */
public record WithdrawCommand ( AccountId accountId, BigDecimal amount, String description, LocalDate today, Overrides overrides )
		implements Command<BankingEvent>, Overriding {

	public WithdrawCommand {
		if ( accountId == null || amount == null || amount.signum() <= 0 ) {
			// a malformed request: rejected before any history is read (WHERE-VALIDATIONS-GO.md, step 2)
			throw new IllegalArgumentException("a withdrawal needs an account and a positive amount");
		}
		overrides = ( overrides == null ) ? Overrides.none() : overrides;
	}

	/** A withdrawal made today, overriding nothing. */
	public WithdrawCommand ( AccountId accountId, BigDecimal amount, String description ) {
		this(accountId, amount, description, LocalDate.now(ZoneOffset.UTC), Overrides.none());
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {

		var period = new ActivePeriodDecisionModel(accountId);
		var exceptionsToday = new OverdraftExceptionsTodayDecisionModel(context.actor(), today);
		var result = context.decisionModels(period, exceptionsToday);

		// ── requests that make no sense: rejected, there is nothing to override ─────
		BusinessException.when(!period.accountExists(), "Account does not exist");
		BusinessException.when(period.isPeriodClosed(),
			"Period " + period.activeMonth() + " is closed, cannot withdraw");

		// ── the bank's rules, each at its own enforcement level ─────────────────────
		BigDecimal balanceAfter = period.balance().subtract(amount);

		context.check(MAXIMUM_WITHDRAWAL, amount.compareTo(MAXIMUM_WITHDRAWAL_AMOUNT) > 0,
			"A withdrawal of " + amount + " exceeds the maximum of " + MAXIMUM_WITHDRAWAL_AMOUNT);

		context.check(NO_OVERDRAFT, balanceAfter.signum() < 0,
			"The balance would become " + balanceAfter)
			.overridableWhen(exceptionsToday.mayGrantAnother(), exceptionsToday.whyNot());

		context.check(LARGE_WITHDRAWAL_JUSTIFIED, amount.compareTo(LARGE_WITHDRAWAL_AMOUNT) > 0,
			"A withdrawal of " + amount + " is above " + LARGE_WITHDRAWAL_AMOUNT + " and has to be justified afterwards");

		context.check(WITHDRAWAL_DESCRIBED, description == null || description.isBlank(),
			"No description given");

		// ── raise as if everything were allowed: the kernel stops what may not go ahead
		result.raiseEvent(
			new MoneyWithdrawn(accountId, period.activeMonth(), amount, description, context.ruleViolations()),
			Tags.of(
				BankingDomainWithClosingTheBooks.ACCOUNT.tag(accountId),
				BankingDomainWithClosingTheBooks.MONTH.tag(BankingDomainWithClosingTheBooks.monthId(period.activeMonth()))
			)
		);
	}
}
