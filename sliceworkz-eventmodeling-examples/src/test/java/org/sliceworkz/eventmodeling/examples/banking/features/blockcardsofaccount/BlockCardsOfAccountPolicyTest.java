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
package org.sliceworkz.eventmodeling.examples.banking.features.blockcardsofaccount;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ACCOUNT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CARD;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CUSTOMER;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountFrozen;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardBlocked;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardIssued;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CardId;
import org.sliceworkz.eventmodeling.testing.PolicyTest;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Whenever an account is frozen or closed, its cards are blocked: each card still usable once, the cards of
 * other accounts and those already blocked left alone, and nothing raised when the event comes round again.
 * The history is seeded the way the commands tag it — the account on every event, the card beside it — since
 * that is what the command's decision model finds the cards by.
 */
public class BlockCardsOfAccountPolicyTest extends PolicyTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final AccountId ACCOUNT_1 = ACCOUNT.id("acc-1");
	private static final AccountId ACCOUNT_2 = ACCOUNT.id("acc-2");
	private static final CardId CARD_A = CARD.id("card-a");
	private static final CardId CARD_B = CARD.id("card-b");
	private static final CardId CARD_C = CARD.id("card-c");
	private static final LocalDate ISSUED_ON = LocalDate.of(2025, 1, 2);

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
	public Policy<BankingEvent> policy ( ) {
		return new BlockCardsOfAccountPolicy();
	}

	@Test
	void freezingAnAccountBlocksItsCards ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
			.and(issued(ACCOUNT_1, CARD_B), cardTags(ACCOUNT_1, CARD_B))
			.and(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.events(
				new CardBlocked(ACCOUNT_1, CARD_A, "account frozen: suspected fraud"),
				new CardBlocked(ACCOUNT_1, CARD_B, "account frozen: suspected fraud"));
	}

	@Test
	void closingAnAccountBlocksItsCardsToo ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
			.and(new AccountClosed(ACCOUNT_1, LocalDate.of(2025, 3, 1)), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.event(new CardBlocked(ACCOUNT_1, CARD_A, "account closed"), cardTags(ACCOUNT_1, CARD_A));
	}

	@Test
	void theCardsOfOtherAccountsAndThoseAlreadyBlockedAreLeftAlone ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(opened(ACCOUNT_2), ACCOUNT.tags(ACCOUNT_2))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
			.and(issued(ACCOUNT_1, CARD_B), cardTags(ACCOUNT_1, CARD_B))
			.and(issued(ACCOUNT_2, CARD_C), cardTags(ACCOUNT_2, CARD_C))
			.and(new CardBlocked(ACCOUNT_1, CARD_A, "reported lost"), cardTags(ACCOUNT_1, CARD_A))
			.and(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.events(new CardBlocked(ACCOUNT_1, CARD_B, "account frozen: suspected fraud"));
	}

	@Test
	void anAccountWithoutCardsHasNothingToBlock ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.noEvents();
	}

	@Test
	void aFrozenAccountClosedLaterBlocksNothingTwice ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
			.and(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.events(new CardBlocked(ACCOUNT_1, CARD_A, "account frozen: suspected fraud"))
		.and()
			.and(new AccountClosed(ACCOUNT_1, LocalDate.of(2025, 3, 1)), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.noEvents();
	}

	@Test
	void aRedeliveredEventIsNotReactedToAgain ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
			.and(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
		.whenReacting()
			.events(new CardBlocked(ACCOUNT_1, CARD_A, "account frozen: suspected fraud"))
		.and()
		.whenRedelivered()
			.noEvents();
	}

	@Test
	void otherEventsAreNotReactedTo ( ) {
		given(opened(ACCOUNT_1), ACCOUNT.tags(ACCOUNT_1))
			.and(issued(ACCOUNT_1, CARD_A), cardTags(ACCOUNT_1, CARD_A))
		.whenReacting()
			.noEvents();
	}

	private static AccountOpened opened ( AccountId accountId ) {
		return new AccountOpened(accountId, CUSTOMER.id("cust-1"), YearMonth.of(2025, 1), LocalDate.of(2025, 1, 1));
	}

	private static CardIssued issued ( AccountId accountId, CardId cardId ) {
		return new CardIssued(accountId, cardId, ISSUED_ON);
	}

	private static Tags cardTags ( AccountId accountId, CardId cardId ) {
		return Tags.of(ACCOUNT.tag(accountId), CARD.tag(cardId));
	}
}
