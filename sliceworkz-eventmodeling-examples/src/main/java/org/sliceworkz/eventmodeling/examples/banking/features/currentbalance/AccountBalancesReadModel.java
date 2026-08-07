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
import java.util.HashMap;
import java.util.Map;

import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * The balance of every account, projected in the background — the base a
 * {@link CurrentBalanceReadModel} reads starts from.
 *
 * <p>An ordinary eventually consistent read model, except in one respect: it folds into an immutable
 * state and publishes that state together with the position it reflects. That is what lets a reader
 * take the two as one observation, which is the whole basis of catching it up correctly. A read model
 * that mutated a {@code Map} field from {@code when} could offer neither — a reader would see a
 * half-applied batch, and there would be no position to pair the answer with.
 *
 * <p>Note that the state is replaced per event here, which is fine for a handful of accounts and is
 * not what a real one would do: at that size, reach for a persistent map so a batch costs one
 * structural copy rather than one per event.
 */
public class AccountBalancesReadModel extends PublishingReadModel<BankingEvent,Map<DomainConceptId,BigDecimal>> {

	@Override
	protected Map<DomainConceptId,BigDecimal> initialState ( ) {
		return Map.of();
	}

	@Override
	protected Map<DomainConceptId,BigDecimal> apply ( Map<DomainConceptId,BigDecimal> state, Event<BankingEvent> event ) {
		DomainConceptId account = BalanceFold.accountOf(event.data());

		Map<DomainConceptId,BigDecimal> next = new HashMap<>(state);
		next.put(account, BalanceFold.apply(state.getOrDefault(account, BigDecimal.ZERO), event.data()));
		return Map.copyOf(next);
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

	/**
	 * The balance of one account as this read model last saw it — as of {@link #upTo()}, no later.
	 *
	 * @param accountId the account to report on
	 * @return its balance, zero for an account this projection has not seen
	 */
	public BigDecimal balanceOf ( DomainConceptId accountId ) {
		return state().getOrDefault(accountId, BigDecimal.ZERO);
	}

}
