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
package org.sliceworkz.eventmodeling.examples.banking.features.deposit;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BALANCE_WITHIN_GUARANTEE;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ORIGIN_OF_FUNDS_EXPLAINED;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
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
 * The deposit's two rules: an override that costs an explanation, and an enforcement deferred to a follow-up.
 */
public class DepositCommandTest extends CommandTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final AccountId ACCOUNT_1 = BankingDomainWithClosingTheBooks.ACCOUNT.id("acc-1");
	private static final YearMonth JANUARY = YearMonth.of(2025, 1);

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

	@Test
	void anOrdinaryDepositRecordsNoViolation ( ) {
		given()
			.event(accountOpened(), accountTags())
			.when(new DepositCommand(ACCOUNT_1, new BigDecimal("500"), "salary"))
			.then()
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("500"), "salary"), periodTags());
	}

	// ── origin-of-funds-explained: an override with explanation ──────────

	@Test
	void aLargeDepositAsksForAnExplanation ( ) {
		var evaluation = given()
			.event(accountOpened(), accountTags())
			.whenEvaluated(new DepositCommand(ACCOUNT_1, new BigDecimal("12000"), "car sold"))
			.thenEvaluation()
			.needsOverrideOf(ORIGIN_OF_FUNDS_EXPLAINED)
			.evaluation();

		// what makes a front end show a text field next to the checkbox
		assertTrue(evaluation.judgementOf(ORIGIN_OF_FUNDS_EXPLAINED).orElseThrow().explanationRequired());
	}

	@Test
	void aTickedBoxWithoutExplanationIsNotEnough ( ) {
		given()
			.event(accountOpened(), accountTags())
			.when(new DepositCommand(ACCOUNT_1, new BigDecimal("12000"), "car sold", Overrides.of(ORIGIN_OF_FUNDS_EXPLAINED.id())))
			.then()
			.rulesViolated()
			.needsOverrideOf(ORIGIN_OF_FUNDS_EXPLAINED);
	}

	@Test
	void anExplainedLargeDepositGoesThroughWithItsExplanationRecorded ( ) {
		given()
			.event(accountOpened(), accountTags())
			.when(new DepositCommand(ACCOUNT_1, new BigDecimal("12000"), "car sold",
				Overrides.none().with(ORIGIN_OF_FUNDS_EXPLAINED, "Proceeds of selling a car")))
			.then()
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("12000"), "car sold", List.of(
					new RuleViolation(ORIGIN_OF_FUNDS_EXPLAINED.id(), EnforcementLevel.OVERRIDE_WITH_EXPLANATION, Disposition.OVERRIDDEN,
						"A deposit of 12000 is above 10000: explain where the money comes from", "Proceeds of selling a car"))),
				Tags.of(RuleTags.overridden(ORIGIN_OF_FUNDS_EXPLAINED)));
	}

	// ── balance-within-guarantee: deferred enforcement ───────────────────

	@Test
	void aDepositOverTheGuaranteeGoesAheadUnasked ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("95000"), "inheritance"), periodTags())
			.whenEvaluated(new DepositCommand(ACCOUNT_1, new BigDecimal("9000"), "bonus"))
			.thenEvaluation()
			.wouldSucceed()
			.judged(BALANCE_WITHIN_GUARANTEE, Verdict.DEFERRED);
	}

	@Test
	void aDepositOverTheGuaranteeIsTaggedForItsFollowUp ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("95000"), "inheritance"), periodTags())
			.when(new DepositCommand(ACCOUNT_1, new BigDecimal("9000"), "bonus"))
			.then()
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("9000"), "bonus", List.of(
					new RuleViolation(BALANCE_WITHIN_GUARANTEE.id(), EnforcementLevel.DEFERRED_ENFORCEMENT, Disposition.ENFORCEMENT_DEFERRED,
						"The balance would become 104000, above the guaranteed 100000; the customer will be informed", null))),
				// what DepositsAboveGuaranteeTodoList selects the deposit by
				Tags.of(RuleTags.deferred(BALANCE_WITHIN_GUARANTEE)));
	}

	private static AccountOpened accountOpened ( ) {
		return new AccountOpened(ACCOUNT_1, BankingDomainWithClosingTheBooks.CUSTOMER.id("cust-1"), JANUARY, LocalDate.of(2025, 1, 1));
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
}
