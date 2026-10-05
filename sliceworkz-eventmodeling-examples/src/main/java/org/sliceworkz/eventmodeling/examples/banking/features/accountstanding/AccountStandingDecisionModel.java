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
package org.sliceworkz.eventmodeling.examples.banking.features.accountstanding;

import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountFrozen;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Whether an account exists, and whether it was frozen or closed: what issuing a card, freezing an account and
 * closing one decide on.
 * <p>
 * Read inside a command, it is also the consistency boundary that keeps a card from slipping past the
 * {@code BlockCardsOfAccountPolicy}: a card issued while the account is being frozen read "not frozen", so the
 * {@code AccountFrozen} appended in between conflicts with its append, and the retry is rejected.
 */
public class AccountStandingDecisionModel implements DecisionModel<BankingEvent> {

	private final AccountId accountId;
	private boolean opened;
	private boolean frozen;
	private boolean closed;

	public AccountStandingDecisionModel ( AccountId accountId ) {
		this.accountId = accountId;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class, AccountFrozen.class, AccountClosed.class),
			BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId));
	}

	@Override
	public void when ( Event<BankingEvent> event ) {
		switch ( event.data() ) {
			case AccountOpened ignored -> opened = true;
			case AccountFrozen ignored -> frozen = true;
			case AccountClosed ignored -> closed = true;
			default -> { }
		}
	}

	public boolean opened ( ) { return opened; }
	public boolean frozen ( ) { return frozen; }
	public boolean closed ( ) { return closed; }

}
