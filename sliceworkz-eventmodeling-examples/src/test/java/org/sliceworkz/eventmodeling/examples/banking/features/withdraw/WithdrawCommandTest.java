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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

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
import org.sliceworkz.eventmodeling.testing.CommandTest;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The worked example of testing a command's business rules with {@link CommandTest}: the history
 * is seeded with {@code given}, the command runs against it, and {@code then()} says what came
 * out — an event, or a rejection by its message.
 * <p>
 * A rejected rule is a {@link BusinessException}, and the last test pins the type rather than the
 * message: that is what keeps a rule saying no apart from a bug in a catch block and in the
 * {@code CommandFailed} event, and it is the one thing {@code error(message)} does not check.
 */
public class WithdrawCommandTest extends CommandTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

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
	void withdrawsFromAnOpenPeriodWithSufficientBalance ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("100"), "salary"), periodTags(JANUARY))
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("40"), "groceries"))
			.then()
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("40"), "groceries"), periodTags(JANUARY));
	}

	@Test
	void rejectsAWithdrawalExceedingTheBalance ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("100"), "salary"), periodTags(JANUARY))
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("250"), "television"))
			.then()
			.error("Insufficient balance: 100 < 250");
	}

	@Test
	void rejectsAWithdrawalFromAClosedPeriod ( ) {
		given()
			.event(accountOpened(), accountTags())
			.event(new MoneyDeposited(ACCOUNT_1, JANUARY, new BigDecimal("100"), "salary"), periodTags(JANUARY))
			.event(new MonthClosed(ACCOUNT_1, JANUARY, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100"),
				BigDecimal.ZERO, 1, LocalDate.of(2025, 2, 1)), periodTags(JANUARY))
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "late"))
			.then()
			.error("Period 2025-01 is closed, cannot withdraw");
	}

	@Test
	void rejectsAWithdrawalFromAnUnknownAccount ( ) {
		given()
			.when(new WithdrawCommand(ACCOUNT_1, new BigDecimal("10"), "nothing to take it from"))
			.then()
			.error("Account does not exist");
	}

	/**
	 * A rule rejection is a {@code BusinessException} — never an {@code IllegalStateException},
	 * which is what a bug throws. {@code error(message)} above compares the message only, so this
	 * is the test that holds the command to the type.
	 */
	@Test
	void aRejectedRuleIsABusinessException ( ) {
		given()
			.event(accountOpened(), accountTags());

		BusinessException rejection = assertThrows(BusinessException.class,
			() -> kernel().execute(new WithdrawCommand(ACCOUNT_1, new BigDecimal("1"), "empty account")));

		assertEquals("Insufficient balance: 0 < 1", rejection.getMessage());
	}

	private static AccountOpened accountOpened ( ) {
		return new AccountOpened(ACCOUNT_1, BankingDomainWithClosingTheBooks.CUSTOMER.id("cust-1"), JANUARY, LocalDate.of(2025, 1, 1));
	}

	private static Tags accountTags ( ) {
		return BankingDomainWithClosingTheBooks.ACCOUNT.tags(ACCOUNT_1);
	}

	private static Tags periodTags ( YearMonth month ) {
		return Tags.of(
			BankingDomainWithClosingTheBooks.ACCOUNT.tag(ACCOUNT_1),
			BankingDomainWithClosingTheBooks.MONTH.tag(BankingDomainWithClosingTheBooks.monthId(month))
		);
	}
}
