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
package org.sliceworkz.eventmodeling.examples.banking.features.justifywithdrawal;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.RulebookFollowUp.WithdrawalJustified;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * The large withdrawals that went ahead under the post-justified override of
 * {@code large-withdrawal-justified} and are still waiting for their justification: the follow-up that makes
 * "justified afterwards" an obligation rather than a hope.
 * <p>
 * It reads two things. The withdrawals come in by the tag the kernel put on them,
 * {@code x-rule-justification-pending:large-withdrawal-justified} — so the read model never has to look inside
 * a payload to find them, and would find them just the same in a domain whose events did not record the
 * violation at all. The {@code WithdrawalJustified} events take them off again, the way a todo list's items
 * leave: by a fact, never by a call. The kernel's tag stays on the withdrawal after it was justified, since
 * events are never rewritten; the justification is what is subtracted.
 * <p>
 * Each entry says who made the exception, read back from the {@code x-actor} tag with
 * {@link Tracing#readFrom(Event)}, and when — which is what an escalation needs. A bank enforcing a deadline
 * would add an automation over a todo list of the same shape, offering an item once its withdrawal is older
 * than the deadline and raising an escalation event for it (see {@code PaymentsToExecuteTodoList} for a todo
 * list withholding what is not yet due).
 * <p>
 * A live model: every read replays the pending withdrawals and their justifications, which the tag keeps to
 * exactly the events that matter. See CHOOSING-A-READ-MODEL.md for when that stops being enough.
 */
public class OverridesAwaitingJustificationReadModel implements ReadModel<BankingEvent> {

	/**
	 * One withdrawal still to be justified.
	 *
	 * @param withdrawal the id of the {@code MoneyWithdrawn} event, the reference to justify it by
	 * @param accountId the account
	 * @param amount the amount paid out
	 * @param teller who made the exception
	 * @param madeAt when
	 */
	public record AwaitingJustification ( String withdrawal, AccountId accountId, BigDecimal amount, String teller, Instant madeAt ) { }

	private final Map<String, AwaitingJustification> awaiting = new LinkedHashMap<>();

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), Tags.of(RuleTags.justificationPending(LARGE_WITHDRAWAL_JUSTIFIED)))
			.or(EventQuery.forEvents(EventTypesFilter.of(WithdrawalJustified.class), Tags.none()));
	}

	@Override
	public void when ( Event<BankingEvent> event ) {
		switch ( event.data() ) {
			case MoneyWithdrawn withdrawn -> awaiting.put(event.reference().id().value(), new AwaitingJustification(
				event.reference().id().value(), withdrawn.accountId(), withdrawn.amount(), Tracing.readFrom(event).actor(), event.timestamp()));
			case WithdrawalJustified justified -> awaiting.remove(justified.withdrawal());
			default -> { }
		}
	}

	/**
	 * @return the withdrawals still to be justified, oldest first
	 */
	public List<AwaitingJustification> awaitingJustification ( ) {
		return new ArrayList<>(awaiting.values());
	}

}
