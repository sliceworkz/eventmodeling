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

import java.math.BigDecimal;
import java.util.Optional;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventmodeling.readmodels.SeededReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * The balance of one account, current as of the moment it is read.
 *
 * <p>Queried via {@code bc.read(CurrentBalanceReadModel.class, balances, accountId)}.
 *
 * <p><b>Why this is not a live model.</b> {@code CurrentPeriodReadModel} in this same example
 * replays every event of an account to answer, which is what "Closing The Books" is there to bound.
 * This takes the other route: start from what the background projection already has, and project only
 * what has not reached it — one event, usually none, whatever the account's history.
 *
 * <p><b>Why it is not simply reading {@link AccountBalancesReadModel}.</b> That projection is behind
 * by however far its thread has got, which is fine for a dashboard and not fine for a decision. The
 * deposit made a moment ago may not be in it yet; it is in the stream, so it is in this answer.
 *
 * <p><b>The three things that make it correct</b>, and all three are in {@code seed()} below: the
 * state and the position come from one observation, an account with no balance yet is reported as
 * zero <em>at that position</em> rather than as no base at all, and the fold applied to the delta is
 * the same {@link BalanceFold} the background projection uses.
 */
public class CurrentBalanceReadModel implements SeededReadModel<BankingEvent> {

	private final AccountBalancesReadModel balances;
	private final AccountId accountId;

	private BigDecimal balance = BigDecimal.ZERO;
	private EventReference upTo;
	private int foldedInRead;

	public CurrentBalanceReadModel ( AccountBalancesReadModel balances, AccountId accountId ) {
		this.balances = balances;
		this.accountId = accountId;
	}

	@Override
	public Optional<EventReference> seed ( ) {
		// One volatile read. Asking for the balance and the position separately would straddle whatever
		// the projector published in between, and the delta would then double-count or skip it.
		ReadModelResult<java.util.Map<AccountId,BigDecimal>> published = balances.published();

		// An account this projection has never seen is a balance of zero AT that position -- not an
		// absent base. Returning empty here would be just as correct and would replay the entire
		// stream on every read of a new account, which is the expensive way to be right.
		balance = published.data().getOrDefault(accountId, BigDecimal.ZERO);
		upTo = published.upTo();
		return Optional.ofNullable(upTo);
	}

	/**
	 * Only this account's events, so the delta is the handful of events one account is behind by
	 * rather than everything the deployment has appended since.
	 */
	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.any(),
				Tags.of(BankingDomainWithClosingTheBooks.ACCOUNT.tag(accountId)));
	}

	@Override
	public void when ( Event<BankingEvent> event ) {
		balance = BalanceFold.apply(balance, event.data());
		upTo = event.reference();
		foldedInRead++;
	}

	public BigDecimal balance ( ) {
		return balance;
	}

	/**
	 * The reference this answer is current to. A caller that has just executed a command can check its
	 * own write is included with {@code writeRef.happenedBefore(upTo())}.
	 *
	 * @return the last event reflected in this balance, base or delta
	 */
	public EventReference upTo ( ) {
		return upTo;
	}

	/** How many events this read had to project on top of its base — the cost of being current. */
	public int foldedInRead ( ) {
		return foldedInRead;
	}

}
