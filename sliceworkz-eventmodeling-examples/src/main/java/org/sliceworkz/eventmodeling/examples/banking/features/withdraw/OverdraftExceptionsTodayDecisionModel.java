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
package org.sliceworkz.eventmodeling.examples.banking.features.withdraw;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.DAILY_OVERDRAFT_EXCEPTIONS;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.NO_OVERDRAFT;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Whether the teller executing a withdrawal may grant another overdraft today: the decision behind
 * {@code overridableWhen} on the {@code no-overdraft} rule.
 * <p>
 * The history it reads is written by the kernel, not by the domain: every event raised with an override
 * carries {@code x-rule-overridden:<rule>}, and every event carries the {@code x-actor} tag of whoever caused
 * it, so {@link RuleTags#overriddenBy(String, org.sliceworkz.eventmodeling.rules.BusinessRule)} selects exactly
 * this teller's overdraft exceptions — across all accounts, which is the point of a per-teller quota. Nothing
 * in {@code MoneyWithdrawn} had to be designed for it.
 * <p>
 * As a decision model its query is part of the command's consistency boundary: a concurrent overdraft granted
 * by the same teller conflicts with this withdrawal, and the retry decides on a count that includes it.
 * <p>
 * The day is judged on the event's timestamp in UTC — the moment the store persisted the event. The cost is a
 * replay of this teller's overdraft exceptions ever made, which the tags keep small; a bank with tellers
 * granting thousands would bound the replay with a daily savepoint, as {@code ActivePeriodDecisionModel} does
 * with its periods.
 */
public class OverdraftExceptionsTodayDecisionModel implements DecisionModel<BankingEvent> {

	private final Optional<String> teller;
	private final LocalDate today;
	private int exceptionsToday;

	/**
	 * @param teller the actor executing the withdrawal, if identified
	 * @param today the day to count in
	 */
	public OverdraftExceptionsTodayDecisionModel ( Optional<String> teller, LocalDate today ) {
		this.teller = teller;
		this.today = today;
	}

	@Override
	public EventQuery eventQuery ( ) {
		// an anonymous caller has no quota to count: it may not grant an overdraft at all
		return teller
			.map(t -> EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), RuleTags.overriddenBy(t, NO_OVERDRAFT)))
			.orElse(EventQuery.matchNone());
	}

	@Override
	public void when ( Event<BankingEvent> event ) {
		if ( event.timestamp().atZone(ZoneOffset.UTC).toLocalDate().equals(today) ) {
			exceptionsToday++;
		}
	}

	/**
	 * @return whether this teller may grant one more overdraft today
	 */
	public boolean mayGrantAnother ( ) {
		return teller.isPresent() && exceptionsToday < DAILY_OVERDRAFT_EXCEPTIONS;
	}

	/**
	 * @return what to tell a teller who may not
	 */
	public String whyNot ( ) {
		return teller.isEmpty()
			? "Only an identified teller can authorize an overdraft"
			: "You already granted " + exceptionsToday + " overdraft exceptions today, the maximum is " + DAILY_OVERDRAFT_EXCEPTIONS;
	}

	/**
	 * @return how many overdraft exceptions this teller granted today
	 */
	public int exceptionsToday ( ) {
		return exceptionsToday;
	}
}
