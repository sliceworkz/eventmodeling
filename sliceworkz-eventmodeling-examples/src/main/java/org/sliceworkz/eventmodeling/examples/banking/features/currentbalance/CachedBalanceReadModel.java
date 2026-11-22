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

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ACCOUNT;

import java.math.BigDecimal;
import java.time.Duration;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;
import org.sliceworkz.eventmodeling.readmodels.CacheKeys;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * The balance of the accounts being worked with, held in memory, and no others.
 * <p>
 * The third way this slice answers "what is the balance of account X", beside
 * {@link AccountBalancesReadModel} (every account, in the background) and {@link CurrentBalanceReadModel}
 * (current, by catching that one up per read). This one is for a bank with far too many accounts to
 * hold them all: the first read of an account replays that account's events and keeps the balance, the
 * projector folds every later movement into it, and the 1.000 accounts read least recently go first.
 * <p>
 * All three fold with {@link BalanceFold}: written once, so the three cannot disagree about what a
 * movement does to a balance.
 * <p>
 * Registered as an instance, eventually consistent, and read by its class:
 * <pre>{@code
 * builder.readmodel(new CachedBalanceReadModel()).eventuallyConsistent();
 * BigDecimal balance = bc.read(CachedBalanceReadModel.class).get(accountId);
 * }</pre>
 */
public class CachedBalanceReadModel extends CachingReadModel<BankingEvent, AccountId, BigDecimal> {

	public CachedBalanceReadModel ( ) {
		this(CachePolicy.maxEntries(1_000).expireAfterAccess(Duration.ofMinutes(30)));
	}

	public CachedBalanceReadModel ( CachePolicy policy ) {
		super(CacheKeys.byEntity(ACCOUNT), policy);
	}

	/** The events that move a balance; every one of them is tagged with its account. */
	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(AccountOpened.class, MoneyDeposited.class, MoneyWithdrawn.class, MonthOpened.class);
	}

	@Override
	protected BigDecimal initialState ( AccountId account ) {
		return BigDecimal.ZERO;
	}

	@Override
	protected BigDecimal apply ( BigDecimal balance, Event<BankingEvent> event ) {
		return BalanceFold.apply(balance, event.data());
	}

}
