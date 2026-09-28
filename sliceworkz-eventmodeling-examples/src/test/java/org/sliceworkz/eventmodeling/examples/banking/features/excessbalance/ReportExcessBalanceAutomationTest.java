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
package org.sliceworkz.eventmodeling.examples.banking.features.excessbalance;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BALANCE_WITHIN_GUARANTEE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.features.excessbalance.DepositsAboveGuaranteeTodoList.ExcessBalanceToReport;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventmodeling.testing.AutomationTest;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The follow-up of a deferred enforcement: a deposit tagged {@code x-rule-deferred} is picked up, the excess
 * balance is reported once, and the deposit leaves the todo list — also when the same item is handed over
 * twice.
 */
public class ReportExcessBalanceAutomationTest extends AutomationTest<ExcessBalanceToReport, BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

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

	@Override
	public Automation<ExcessBalanceToReport, BankingEvent, BankingOutboundEvent> automation ( ) {
		return new ReportExcessBalanceAutomation(new DepositsAboveGuaranteeTodoList());
	}

	@Test
	void anOrdinaryDepositIsNotPickedUp ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("500"), "salary"), periodTags())
			.expectNoTodoItems();
	}

	@Test
	void aDeferredEnforcementIsCarriedOutOnce ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(depositOverTheGuarantee(), periodTags().merge(Tags.of(RuleTags.deferred(BALANCE_WITHIN_GUARANTEE))))
			.whenBatchRuns()
			.itemsHandled(1)
			.and()
			// the ExcessBalanceReported event reaches the todo list at the start of the next round
			.expectNoTodoItems();
	}

	@Test
	void aRedeliveredItemIsReportedOnce ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(depositOverTheGuarantee(), periodTags().merge(Tags.of(RuleTags.deferred(BALANCE_WITHIN_GUARANTEE))))
			.whenBatchRuns()
			.itemsHandled(1)
			.and()
			.whenItemsAreRedelivered()
			.itemsHandled(1)
			.noEvents();	// the idempotency key derived from the deposit swallowed the repeat
	}

	private static MoneyDeposited depositOverTheGuarantee ( ) {
		return new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("104000"), "inheritance", List.of(new RuleViolation(
			BALANCE_WITHIN_GUARANTEE.id(), EnforcementLevel.DEFERRED_ENFORCEMENT, Disposition.ENFORCEMENT_DEFERRED, "over the guarantee", null)));
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
