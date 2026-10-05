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

import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountFrozen;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Whenever an account is frozen or closed, block its cards.
 * <p>
 * Two variants of one fact lead to the same reaction, so the query names both and each maps onto the same
 * command. The policy only maps: the account and the reason come from the event itself, and which cards are
 * still to block is the command's decision, on its own decision model. That is what keeps the reaction a
 * function of the event, and safe to redeliver.
 */
public class BlockCardsOfAccountPolicy implements Policy<BankingEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forTypes(AccountFrozen.class, AccountClosed.class);
	}

	@Override
	public Optional<Command<BankingEvent>> react ( Event<BankingEvent> event ) {
		return switch ( event.data() ) {
			case AccountFrozen frozen -> Optional.of(new BlockCardsOfAccountCommand(frozen.accountId(), "account frozen: " + frozen.reason()));
			case AccountClosed closed -> Optional.of(new BlockCardsOfAccountCommand(closed.accountId(), "account closed"));
			default -> Optional.empty();
		};
	}

}
