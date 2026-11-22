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
package org.sliceworkz.eventmodeling.examples.banking.features.currentbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ACCOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CUSTOMER;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;
import org.sliceworkz.eventmodeling.testing.CachingReadModelTest;

/**
 * The worked example of testing a {@link CachingReadModel} with the published {@link CachingReadModelTest}:
 * a first read loads, a held account stays behind until the projector delivers the next movement and is
 * current after it without being loaded again, and an account let go for the room loads again with
 * nothing missing.
 */
public class CachedBalanceReadModelTest extends CachingReadModelTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent, AccountId, BigDecimal> {

	private static final YearMonth JANUARY = YearMonth.of(2025, 1);
	private static final AccountId CHECKING = ACCOUNT.id("checking");
	private static final AccountId SAVINGS = ACCOUNT.id("savings");
	private static final AccountId HOLIDAY = ACCOUNT.id("holiday");

	@Override
	public CachingReadModel<BankingEvent, AccountId, BigDecimal> readModel ( ) {
		return new CachedBalanceReadModel(CachePolicy.maxEntries(2));
	}

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
	void aHeldAccountIsKeptCurrentByTheProjectorWithoutBeingLoadedAgain ( ) {
		given()
			.events(opened(CHECKING), deposited(CHECKING, "1000"), withdrawn(CHECKING, "150"))
			.whenRead(CHECKING).loaded().is(new BigDecimal("850"))
		.and()
			.event(deposited(CHECKING, "2500"))
			.whenRead(CHECKING).fromMemory().is(new BigDecimal("850"))
		.and()
			.projected()
			.whenRead(CHECKING).fromMemory().is(new BigDecimal("3350"));

		assertEquals(1, readModelUnderTest().statistics().misses());
	}

	@Test
	void anAccountLetGoForTheRoomLoadsAgainWithNothingMissing ( ) {
		given()
			.events(opened(CHECKING), opened(SAVINGS), opened(HOLIDAY))
			.whenRead(CHECKING).loaded().is(BigDecimal.ZERO)
		.and()
			.whenRead(SAVINGS).loaded().is(BigDecimal.ZERO)
		.and()
			.whenRead(HOLIDAY).loaded().is(BigDecimal.ZERO)       // the third account: checking goes
		.and()
			.event(deposited(CHECKING, "40"))
			.projected()                                          // delivered while checking is not held
			.whenRead(CHECKING).loaded().is(new BigDecimal("40"));
	}

	private static AccountOpened opened ( AccountId account ) {
		return new AccountOpened(account, CUSTOMER.id("customer-of-" + account.value()), JANUARY, LocalDate.of(2025, 1, 2));
	}

	private static MoneyDeposited deposited ( AccountId account, String amount ) {
		return new MoneyDeposited(account, JANUARY, new BigDecimal(amount), "deposit");
	}

	private static MoneyWithdrawn withdrawn ( AccountId account, String amount ) {
		return new MoneyWithdrawn(account, JANUARY, new BigDecimal(amount), "withdrawal");
	}

}
