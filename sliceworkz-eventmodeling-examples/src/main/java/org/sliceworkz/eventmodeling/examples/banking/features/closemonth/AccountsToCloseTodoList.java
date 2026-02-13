/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.examples.banking.features.closemonth;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.*;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * TodoList read model that identifies accounts whose current month needs closing.
 * <p>
 * This is the "event-driven discovery" side of the automation pattern.
 * It builds a map of accounts and their active periods, and identifies which ones
 * have an active month that has ended (i.e., the current calendar month is past
 * the account's active month).
 * <p>
 * The automation processor will then execute {@link CloseMonthCommand} for each
 * account in the todo list.
 * <p>
 * <b>Design note:</b> The "month has ended" check is done at read time against
 * a configurable "current month". In production, this would be driven by a
 * time-based trigger (the "Passage of Time" pattern) that publishes a
 * {@code MonthEndReached} inbound event.
 */
public class AccountsToCloseTodoList implements TodoListReadModel<BankingEvent, AccountsToCloseTodoList.AccountToClose> {

	private final YearMonth closingMonth;
	private final Map<String, AccountState> accounts = new HashMap<>();
	private EventReference lastEventReference;

	/**
	 * @param closingMonth the month that should be closed (e.g., the month that just ended)
	 */
	public AccountsToCloseTodoList(YearMonth closingMonth) {
		this.closingMonth = closingMonth;
	}

	@Override
	public EventQuery eventQuery() {
		// Watch all domain events to track account lifecycles
		return EventQuery.matchAll();
	}

	@Override
	public void when(BankingEvent event) {
		switch (event) {
			case AccountOpened ao -> {
				accounts.put(ao.accountId().value(), new AccountState(
					ao.accountId(), ao.initialMonth(), false));
			}
			case MonthClosed mc -> {
				var state = accounts.get(mc.accountId().value());
				if (state != null) {
					accounts.put(mc.accountId().value(), new AccountState(
						state.accountId, state.activeMonth, true));
				}
			}
			case MonthOpened mo -> {
				accounts.put(mo.accountId().value(), new AccountState(
					mo.accountId(), mo.month(), false));
			}
			// Deposits and withdrawals don't affect the todo list
			case MoneyDeposited d -> {}
			case MoneyWithdrawn w -> {}
		}
	}

	@Override
	public void lastEventReference(EventReference eventReference) {
		this.lastEventReference = eventReference;
	}

	@Override
	public Optional<EventReference> lastEventReference() {
		return Optional.ofNullable(lastEventReference);
	}

	/**
	 * Returns accounts whose active month matches the closing month and
	 * whose books have not yet been closed.
	 */
	@Override
	public Stream<AccountToClose> streamItems(Limit limit) {
		return accounts.values().stream()
			.filter(state -> !state.closed)
			.filter(state -> state.activeMonth.equals(closingMonth))
			.map(state -> new AccountToClose(state.accountId, closingMonth))
			.limit(limit.value());
	}

	// ── Internal state ───────────────────────────────────────────────────

	private record AccountState(
		DomainConceptId accountId,
		YearMonth activeMonth,
		boolean closed
	) {}

	// ── Todo item ────────────────────────────────────────────────────────

	public record AccountToClose(
		DomainConceptId accountId,
		YearMonth monthToClose
	) {}
}
