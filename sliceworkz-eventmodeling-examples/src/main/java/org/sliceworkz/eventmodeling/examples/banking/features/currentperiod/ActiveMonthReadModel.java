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
package org.sliceworkz.eventmodeling.examples.banking.features.currentperiod;

import java.time.YearMonth;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.*;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Lightweight live read model that finds the active month for an account
 * using a single backwards query.
 * <p>
 * Queries only {@code AccountOpened} and {@code MonthOpened} events for the
 * account, backwards with limit 1. This returns at most one event — the most
 * recent period-opening event — from which the active month is extracted.
 * <p>
 * Useful for display and informational queries that need to show which month
 * is currently active. Commands no longer need this lookup — the
 * {@link ActivePeriodDecisionModel} discovers the active month itself via
 * {@code initQuery()}.
 * <p>
 * Queried via: {@code bc.read(ActiveMonthReadModel.class, accountId)}
 */
public class ActiveMonthReadModel implements ReadModel<BankingEvent> {

	private final AccountId accountId;

	private boolean accountExists;
	private YearMonth activeMonth;

	public ActiveMonthReadModel(AccountId accountId) {
		this.accountId = accountId;
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(
			EventTypesFilter.of(AccountOpened.class, MonthOpened.class),
			BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId)
		).backwards().limit(1);
	}

	@Override
	public void when(BankingEvent event) {
		switch (event) {
			case AccountOpened ao -> {
				accountExists = true;
				activeMonth = ao.initialMonth();
			}
			case MonthOpened mo -> {
				accountExists = true;
				activeMonth = mo.month();
			}
			default -> {}
		}
	}

	public boolean accountExists() { return accountExists; }
	public YearMonth activeMonth() { return activeMonth; }
}
