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

import java.util.LinkedHashSet;
import java.util.Set;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardBlocked;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardIssued;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CardId;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Blocks every card of an account that is not blocked yet, one {@code CardBlocked} per card.
 * <p>
 * Idempotent by its own rule: with nothing left to block it raises nothing and rejects nothing, so the
 * policy issuing it — or a redelivery of the event it reacted to — never has to know whether it ran before.
 * Its decision model is also its consistency boundary: a card issued while the cards are being blocked
 * conflicts with this append, and the retry blocks it too.
 *
 * @param accountId the account whose cards to block
 * @param reason why, recorded on each {@code CardBlocked}
 */
public record BlockCardsOfAccountCommand ( AccountId accountId, String reason ) implements Command<BankingEvent> {

	public BlockCardsOfAccountCommand {
		if ( accountId == null ) {
			throw new IllegalArgumentException("blocking cards names the account");
		}
		if ( reason == null || reason.isBlank() ) {
			throw new IllegalArgumentException("blocking cards needs a reason");
		}
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
		var cards = new CardsOfAccountDecisionModel(accountId);
		var result = context.decisionModels(cards);

		for ( CardId cardId : cards.usable() ) {
			result.raiseEvent(new CardBlocked(accountId, cardId, reason), Tags.of(ACCOUNT.tag(accountId), CARD.tag(cardId)));
		}
	}

	/**
	 * The cards issued on the account and not blocked since, in the order they were issued.
	 */
	static class CardsOfAccountDecisionModel implements DecisionModel<BankingEvent> {

		private final AccountId accountId;
		private final Set<CardId> usable = new LinkedHashSet<>();

		CardsOfAccountDecisionModel ( AccountId accountId ) {
			this.accountId = accountId;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(CardIssued.class, CardBlocked.class), ACCOUNT.tags(accountId));
		}

		@Override
		public void when ( Event<BankingEvent> event ) {
			switch ( event.data() ) {
				case CardIssued issued -> usable.add(issued.cardId());
				case CardBlocked blocked -> usable.remove(blocked.cardId());
				default -> { }
			}
		}

		Set<CardId> usable ( ) {
			return usable;
		}
	}

}
