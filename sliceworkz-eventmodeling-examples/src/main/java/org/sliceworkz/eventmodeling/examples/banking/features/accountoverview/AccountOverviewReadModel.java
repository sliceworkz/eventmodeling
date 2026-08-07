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
package org.sliceworkz.eventmodeling.examples.banking.features.accountoverview;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Every account that has been opened — an eventually consistent read model, projected in the
 * background, so reading it costs nothing but looking at the state it holds.
 *
 * <p><b>Why it extends {@link PublishingReadModel} rather than keeping a {@code Set} field.</b> A
 * read model is projected on the framework's thread and read from whichever thread asks, so a model
 * that mutates its own collection can hand a reader a half-applied batch — and iterating a collection
 * while it is being added to can throw outright. Folding into an immutable state and publishing it
 * once per batch removes the possibility rather than documenting it, and it comes with the position
 * the state reflects, so a caller can say how current the answer is (see {@link #upTo()}) instead of
 * only hoping.
 *
 * <p>The cost is a copy of the set per batch. That is the trade this base class makes, and it is the
 * right one until the model is large enough to measure otherwise.
 *
 * <p>See {@code CHOOSING-A-READ-MODEL.md} for when a read belongs here rather than on a live model.
 */
public class AccountOverviewReadModel extends PublishingReadModel<BankingDomainEvent,Set<AccountOverviewReadModel.AccountSummary>> {

	/**
	 * A feature slice registers the read model instance the framework is to project, and there is no
	 * container here to hand it one — hence the singleton. It is safe to share now in a way it was not
	 * before: what a reader gets back is an immutable value, not this object's insides.
	 */
	public static AccountOverviewReadModel INSTANCE = new AccountOverviewReadModel();

	@Override
	protected Set<AccountSummary> initialState ( ) {
		return Set.of();
	}

	@Override
	protected Set<AccountSummary> apply ( Set<AccountSummary> accounts, Event<BankingDomainEvent> event ) {
		// exhaustive on purpose: a new domain event should not compile until it has been considered
		// here, which a default branch would quietly prevent
		return switch ( event.data() ) {
			case AccountOpened ao -> with(accounts, new AccountSummary(ao.accountId().value(), ao.customerId().value(), ao.date()));
		};
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

	/**
	 * Every account opened so far, as of {@link #upTo()}.
	 *
	 * @return an immutable snapshot — later batches replace the model's state, they never edit what
	 *         was handed out here
	 */
	public Set<AccountSummary> getAccounts ( ) {
		return state();
	}

	/**
	 * Copy-on-write, which is what keeps a state that has been handed to a reader from changing under
	 * it. {@code LinkedHashSet} rather than {@code Set.copyOf} so the accounts keep the order they were
	 * opened in.
	 */
	private static Set<AccountSummary> with ( Set<AccountSummary> accounts, AccountSummary added ) {
		Set<AccountSummary> next = new LinkedHashSet<>(accounts);
		next.add(added);
		return Collections.unmodifiableSet(next);
	}

	/**
	 * One line of the overview. A record, so it is immutable and safe to hand out with the set.
	 *
	 * @param id the account's identifier
	 * @param customerId the customer the account belongs to
	 * @param openDate the day it was opened
	 */
	public record AccountSummary ( String id, String customerId, LocalDate openDate ) {

	}

}
