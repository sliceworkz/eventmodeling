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

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.MAXIMUM_WITHDRAWAL;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.NO_OVERDRAFT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.WITHDRAWAL_DESCRIBED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventmodeling.testing.CommandTest;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The worked example of testing a command's rules with {@link CommandTest} — both kinds of them.
 * <p>
 * A request that makes no sense (an unknown account, a closed period) is a {@link BusinessException},
 * asserted with {@code businessError(message)}. The bank's behavioral rules are asserted by what the kernel
 * made of them: {@code whenEvaluated(...).thenEvaluation()} for what a teller would be shown before
 * submitting, {@code then().rulesViolated()} for a submit the kernel rejected, and the recorded
 * {@link RuleViolation}s in the event for one it let through. {@code as("alice")} executes as a teller, which
 * is what the command's decision on who may grant an overdraft is taken for.
 * <p>
 * The overdraft quota is tested by seeding history: three earlier overdraft exceptions, appended as the
 * teller and tagged as the kernel tags an override — exactly what the decision model counts.
 */
public class WithdrawCommandTest extends CommandTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final AccountId ACCOUNT_1 = BankingDomainWithClosingTheBooks.ACCOUNT.id("acc-1");
	private static final YearMonth JANUARY = YearMonth.of(2025, 1);
	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Override
	public Class<BankingEvent> domainEventType ( ) {
		return BankingEvent.class;
	}

	@Override
	public Class<BankingInboundEvent> inboundEventType ( ) {
		return BankingInboundEvent.class;
	}

	@Override
	public Class<BankingOutboundEvent> outboundEventType ( ) {
		return BankingOutboundEvent.class;
	}

	// ── an ordinary withdrawal ───────────────────────────────────────────

	@Test
	void withdrawsFromAnOpenPeriodWithSufficientBalance ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("40"), "groceries"))
			.then()
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("40"), "groceries"), periodTags());
	}

	// ── requests that make no sense: a BusinessException, nothing to override ─

	@Test
	void rejectsAWithdrawalFromAClosedPeriod ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.event(new MonthClosed(ACCOUNT_1, JANUARY, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100"),
				BigDecimal.ZERO, 1, LocalDate.of(2025, 2, 1)), periodTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "late"))
			.then()
			.businessError("Period 2025-01 is closed, cannot withdraw");
	}

	@Test
	void rejectsAWithdrawalFromAnUnknownAccount ( ) {
		given()
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "nothing to take it from"))
			.then()
			.businessError("Account does not exist");
	}

	@Test
	void aRequestThatMakesNoSenseIsRejectedBeforeAnyRuleIsJudged ( ) {
		given()
			.as("alice")
			.whenEvaluated(new WithdrawCommand(ACCOUNT_1, new BigDecimal("99999"), "", TODAY, Overrides.none()))
			.thenEvaluation()
			.rejected("Account does not exist");
	}

	// ── no-overdraft: a pre-authorized override, granted by the command ──

	@Test
	void anAnonymousCallerCannotOverdrawAnAccount ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("250"), "television", TODAY, Overrides.of(NO_OVERDRAFT.id())))
			.then()
			.rulesViolated()
			.blockedBy(NO_OVERDRAFT)
			.notOverridable(NO_OVERDRAFT, "Only an identified teller can authorize an overdraft");
	}

	@Test
	void aTellerIsOfferedTheOverdraftAsAnOverride ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.as("alice")
			.whenEvaluated(new WithdrawCommand(ACCOUNT_1, new BigDecimal("250"), "television", TODAY, Overrides.none()))
			.thenEvaluation()
			.needsOverrideOf(NO_OVERDRAFT);
	}

	@Test
	void aTellerGrantsAnOverdraftAndItIsRecorded ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.as("alice")
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("250"), "television", TODAY, Overrides.of(NO_OVERDRAFT.id())))
			.then()
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("250"), "television", List.of(
					new RuleViolation(NO_OVERDRAFT.id(), EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, Disposition.OVERRIDDEN, "The balance would become -150", null))),
				Tags.of(RuleTags.overridden(NO_OVERDRAFT)));
	}

	@Test
	void theFourthOverdraftOfTheDayIsRefused ( ) {
		given()
			.event(accountOpened(), accountTags())
			.as("alice")
			.event(earlierOverdraft(), overdraftTags())
			.event(earlierOverdraft(), overdraftTags())
			.event(earlierOverdraft(), overdraftTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "coffee", TODAY, Overrides.of(NO_OVERDRAFT.id())))
			.then()
			.rulesViolated()
			.blockedBy(NO_OVERDRAFT)
			.notOverridable(NO_OVERDRAFT, "You already granted 3 overdraft exceptions today, the maximum is 3");
	}

	@Test
	void anotherTellersOverdraftsDoNotCountAgainstTheQuota ( ) {
		given()
			.event(accountOpened(), accountTags())
			.as("bob")
			.event(earlierOverdraft(), overdraftTags())
			.event(earlierOverdraft(), overdraftTags())
			.event(earlierOverdraft(), overdraftTags())
			.as("alice")
			.whenEvaluated(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "coffee", TODAY, Overrides.of(NO_OVERDRAFT.id())))
			.thenEvaluation()
			.wouldSucceed()
			.judged(NO_OVERDRAFT, Verdict.OVERRIDDEN);
	}

	// ── maximum-withdrawal: strictly enforced ────────────────────────────

	@Test
	void theMaximumIsStrictlyEnforcedWhateverIsTicked ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100000"), periodTags())
			.as("alice")
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("60000"), "boat", TODAY,
				Overrides.of(MAXIMUM_WITHDRAWAL.id(), LARGE_WITHDRAWAL_JUSTIFIED.id())))
			.then()
			.rulesViolated()
			.blockedBy(MAXIMUM_WITHDRAWAL);
	}

	// ── large-withdrawal-justified: a post-justified override ────────────

	@Test
	void aLargeWithdrawalGoesThroughAwaitingItsJustification ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("10000"), periodTags())
			.as("alice")
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("6000"), "car", TODAY, Overrides.of(LARGE_WITHDRAWAL_JUSTIFIED.id())))
			.then()
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("6000"), "car", List.of(
					new RuleViolation(LARGE_WITHDRAWAL_JUSTIFIED.id(), EnforcementLevel.POST_JUSTIFIED_OVERRIDE, Disposition.JUSTIFICATION_PENDING,
						"A withdrawal of 6000 is above 5000 and has to be justified afterwards", null))),
				Tags.of(RuleTags.overridden(LARGE_WITHDRAWAL_JUSTIFIED), RuleTags.justificationPending(LARGE_WITHDRAWAL_JUSTIFIED)));
	}

	@Test
	void everyViolatedRuleIsShownAtOnce ( ) {
		given()
			.event(accountOpened(), accountTags())
			.as("alice")
			.whenEvaluated(new WithdrawCommand(ACCOUNT_1, new BigDecimal("6000"), " ", TODAY, Overrides.none()))
			.thenEvaluation()
			.needsOverrideOf(NO_OVERDRAFT, LARGE_WITHDRAWAL_JUSTIFIED)
			.judged(WITHDRAWAL_DESCRIBED, Verdict.ADVISED)
			.notViolated(MAXIMUM_WITHDRAWAL);
	}

	// ── withdrawal-described: a guideline ────────────────────────────────

	@Test
	void aWithdrawalWithoutDescriptionIsOnlyAdvisedAgainst ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(deposit("100"), periodTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "", TODAY, Overrides.none()))
			.then()
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("10"), "", List.of(
					new RuleViolation(WITHDRAWAL_DESCRIBED.id(), EnforcementLevel.GUIDELINE, Disposition.GUIDELINE_NOT_FOLLOWED, "No description given", null))),
				Tags.of(RuleTags.notFollowed(WITHDRAWAL_DESCRIBED)));
	}

	/**
	 * A rule violation the kernel rejects on is a {@code BusinessException} too — a rejection, not a bug —
	 * so {@code businessError()} and a {@code catch ( BusinessException )} see it as such.
	 */
	@Test
	void aRuleViolationIsABusinessException ( ) {
		given()
			.event(accountOpened(), accountTags())
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("1"), "empty account"))
			.then()
			.businessError();
	}

	// ── history ──────────────────────────────────────────────────────────

	private static AccountOpened accountOpened ( ) {
		return new AccountOpened(ACCOUNT_1, BankingDomainWithClosingTheBooks.CUSTOMER.id("cust-1"), JANUARY, LocalDate.of(2025, 1, 1));
	}

	private static MoneyDeposited deposit ( String amount ) {
		return new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal(amount), "salary");
	}

	private static MoneyWithdrawn earlierOverdraft ( ) {
		return new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("1"), "earlier", List.of(
			new RuleViolation(NO_OVERDRAFT.id(), EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, Disposition.OVERRIDDEN, "earlier", null)));
	}

	private static Tags accountTags ( ) {
		return BankingDomainWithClosingTheBooks.ACCOUNT.tags(ACCOUNT_1);
	}

	private static Tags periodTags ( ) {
		return Tags.of(
			BankingDomainWithClosingTheBooks.ACCOUNT.tag(ACCOUNT_1),
			BankingDomainWithClosingTheBooks.MONTH.tag(BankingDomainWithClosingTheBooks.monthId(JANUARY))
		);
	}

	/** An earlier overdraft exception as the kernel tagged it: the period's tags plus the override tag. */
	private static Tags overdraftTags ( ) {
		return periodTags().merge(Tags.of(RuleTags.overridden(NO_OVERDRAFT)));
	}
}
