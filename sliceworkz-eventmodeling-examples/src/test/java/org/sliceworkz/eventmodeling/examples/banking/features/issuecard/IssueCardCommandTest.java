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
package org.sliceworkz.eventmodeling.examples.banking.features.issuecard;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ACCOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CARD;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CUSTOMER;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountFrozen;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardIssued;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CardId;
import org.sliceworkz.eventmodeling.testing.CommandTest;
import org.sliceworkz.eventstore.events.Tags;

/**
 * A card is issued on an open account only: a frozen or closed account — whose cards the
 * {@code BlockCardsOfAccountPolicy} blocks — gets no new ones.
 */
public class IssueCardCommandTest extends CommandTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final AccountId ACCOUNT_1 = ACCOUNT.id("acc-1");
	private static final CardId CARD_A = CARD.id("card-a");
	private static final LocalDate TODAY = LocalDate.of(2025, 1, 2);

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
	void aCardIsIssuedOnAnOpenAccount ( ) {
		given()
			.event(opened(), ACCOUNT.tags(ACCOUNT_1))
			.when(new IssueCardCommand(ACCOUNT_1, CARD_A, TODAY))
			.then()
			.event(new CardIssued(ACCOUNT_1, CARD_A, TODAY), Tags.of(ACCOUNT.tag(ACCOUNT_1), CARD.tag(CARD_A)));
	}

	@Test
	void noCardOnAnAccountThatDoesNotExist ( ) {
		given()
			.when(new IssueCardCommand(ACCOUNT_1, CARD_A, TODAY))
			.then()
			.businessError("Account acc-1 does not exist");
	}

	@Test
	void noCardOnAFrozenAccount ( ) {
		given()
			.event(opened(), ACCOUNT.tags(ACCOUNT_1))
			.event(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
			.when(new IssueCardCommand(ACCOUNT_1, CARD_A, TODAY))
			.then()
			.businessError("Account acc-1 is frozen");
	}

	@Test
	void noCardOnAClosedAccount ( ) {
		given()
			.event(opened(), ACCOUNT.tags(ACCOUNT_1))
			.event(new AccountClosed(ACCOUNT_1, TODAY), ACCOUNT.tags(ACCOUNT_1))
			.when(new IssueCardCommand(ACCOUNT_1, CARD_A, TODAY))
			.then()
			.businessError("Account acc-1 is closed");
	}

	private static AccountOpened opened ( ) {
		return new AccountOpened(ACCOUNT_1, CUSTOMER.id("cust-1"), YearMonth.of(2025, 1), LocalDate.of(2025, 1, 1));
	}
}
