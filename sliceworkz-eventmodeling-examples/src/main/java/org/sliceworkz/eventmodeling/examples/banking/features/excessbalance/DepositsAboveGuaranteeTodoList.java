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
package org.sliceworkz.eventmodeling.examples.banking.features.excessbalance;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BALANCE_WITHIN_GUARANTEE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.RulebookFollowUp.ExcessBalanceReported;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * The deposits whose enforcement of {@code balance-within-guarantee} was deferred, and not yet carried out:
 * the queue of a deferred enforcement.
 * <p>
 * Deferred enforcement is the enforcement level that needs no override and no user decision — the deposit
 * always goes ahead — so everything it asks of the framework is the recorded violation, and the kernel's
 * {@code x-rule-deferred:balance-within-guarantee} tag on the deposit is what this todo list selects by. An
 * item leaves when {@code ExcessBalanceReported} names its deposit, as items always leave a todo list: by a
 * fact the projection sees, so it stays gone after a restart.
 */
public class DepositsAboveGuaranteeTodoList implements TodoListReadModel<BankingEvent, DepositsAboveGuaranteeTodoList.ExcessBalanceToReport> {

	/**
	 * One enforcement still to carry out.
	 *
	 * @param deposit the id of the deposit that took the balance over the guarantee
	 * @param accountId the account
	 */
	public record ExcessBalanceToReport ( String deposit, AccountId accountId ) { }

	private final Map<String, ExcessBalanceToReport> outstanding = new LinkedHashMap<>();
	private EventReference lastEventReference;

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(MoneyDeposited.class), Tags.of(RuleTags.deferred(BALANCE_WITHIN_GUARANTEE)))
			.or(EventQuery.forEvents(EventTypesFilter.of(ExcessBalanceReported.class), Tags.none()));
	}

	@Override
	public synchronized void when ( Event<BankingEvent> event ) {
		switch ( event.data() ) {
			case MoneyDeposited deposited -> {
				String deposit = event.reference().id().value();
				outstanding.put(deposit, new ExcessBalanceToReport(deposit, deposited.accountId()));
			}
			case ExcessBalanceReported reported -> outstanding.remove(reported.deposit());
			default -> { }
		}
		lastEventReference = event.reference();
	}

	@Override
	public synchronized Stream<ExcessBalanceToReport> streamItems ( Limit limit ) {
		// a copy, not a view: the automation reads this while the projector may be updating the map
		List<ExcessBalanceToReport> items = new ArrayList<>(outstanding.values());
		return items.stream().limit(limit.value());
	}

	@Override
	public synchronized Optional<EventReference> lastEventReference ( ) {
		return Optional.ofNullable(lastEventReference);
	}

	/** How many enforcements are still to be carried out. */
	public synchronized int outstandingCount ( ) {
		return outstanding.size();
	}

}
