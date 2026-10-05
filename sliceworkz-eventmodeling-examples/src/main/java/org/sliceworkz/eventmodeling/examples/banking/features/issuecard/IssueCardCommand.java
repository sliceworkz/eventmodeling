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

import java.time.LocalDate;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.CardIssued;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CardId;
import org.sliceworkz.eventmodeling.examples.banking.features.accountstanding.AccountStandingDecisionModel;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Issues a card on an account that is open and neither frozen nor closed.
 *
 * @param accountId the account the card pays from
 * @param cardId the card, minted by the caller with {@code CARD.newId()} so it knows which card it issued
 * @param issuedOn the day of issue
 */
public record IssueCardCommand ( AccountId accountId, CardId cardId, LocalDate issuedOn ) implements Command<BankingEvent> {

	public IssueCardCommand {
		if ( accountId == null || cardId == null || issuedOn == null ) {
			throw new IllegalArgumentException("a card is issued on an account, with an id, on a day");
		}
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
		var account = new AccountStandingDecisionModel(accountId);
		var result = context.decisionModels(account);

		BusinessException.when(!account.opened(), "Account " + accountId.value() + " does not exist");
		BusinessException.when(account.closed(), "Account " + accountId.value() + " is closed");
		BusinessException.when(account.frozen(), "Account " + accountId.value() + " is frozen");

		result.raiseEvent(new CardIssued(accountId, cardId, issuedOn), Tags.of(ACCOUNT.tag(accountId), CARD.tag(cardId)));
	}

}
