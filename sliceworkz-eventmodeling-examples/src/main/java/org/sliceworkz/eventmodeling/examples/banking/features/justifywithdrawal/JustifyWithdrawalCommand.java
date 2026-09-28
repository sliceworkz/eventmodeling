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

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.RulebookFollowUp.WithdrawalJustified;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Justifies, afterwards, a withdrawal that went ahead under the post-justified override of
 * {@code large-withdrawal-justified}: the second half of that enforcement level.
 * <p>
 * Everything here is an ordinary command. Whether there is something to justify is read from the withdrawal
 * itself — the violation it recorded in its payload, with disposition {@code JUSTIFICATION_PENDING} — and a
 * justification for a withdrawal that needs none, or has one already, makes no sense and is rejected with a
 * {@link BusinessException}. What the post-justified level added was only the recorded obligation; the
 * framework needs nothing further to follow it up.
 *
 * @param accountId the account of the withdrawal
 * @param withdrawal the id of the {@code MoneyWithdrawn} event, as {@code OverridesAwaitingJustificationReadModel} lists it
 * @param justification why the exception was made
 */
public record JustifyWithdrawalCommand ( AccountId accountId, String withdrawal, String justification ) implements Command<BankingEvent> {

	public JustifyWithdrawalCommand {
		if ( accountId == null || withdrawal == null || withdrawal.isBlank() ) {
			throw new IllegalArgumentException("a justification names the account and the withdrawal it justifies");
		}
		if ( justification == null || justification.isBlank() ) {
			throw new IllegalArgumentException("a justification needs to say something");
		}
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
		var state = new JustificationState(accountId, withdrawal);
		var result = context.decisionModels(state);

		BusinessException.when(!state.withdrawalFound(), "No withdrawal " + withdrawal + " on this account");
		BusinessException.when(!state.justificationPending(), "Withdrawal " + withdrawal + " needs no justification");
		BusinessException.when(state.justified(), "Withdrawal " + withdrawal + " was already justified");

		result.raiseEvent(
			new WithdrawalJustified(accountId, withdrawal, LARGE_WITHDRAWAL_JUSTIFIED.id(), justification.strip()),
			BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId));
	}

	/**
	 * The withdrawal and whether it was justified already, both on the account's own history.
	 */
	static class JustificationState implements DecisionModel<BankingEvent> {

		private final AccountId accountId;
		private final String withdrawal;
		private boolean withdrawalFound;
		private boolean justificationPending;
		private boolean justified;

		JustificationState ( AccountId accountId, String withdrawal ) {
			this.accountId = accountId;
			this.withdrawal = withdrawal;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class, WithdrawalJustified.class),
				BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId));
		}

		@Override
		public void when ( Event<BankingEvent> event ) {
			switch ( event.data() ) {
				case MoneyWithdrawn withdrawn when event.reference().id().value().equals(withdrawal) -> {
					withdrawalFound = true;
					justificationPending = withdrawn.ruleViolations().stream().anyMatch(v ->
						v.isOf(LARGE_WITHDRAWAL_JUSTIFIED) && v.disposition() == Disposition.JUSTIFICATION_PENDING);
				}
				case WithdrawalJustified j when j.withdrawal().equals(withdrawal) -> justified = true;
				default -> { }
			}
		}

		boolean withdrawalFound ( ) { return withdrawalFound; }
		boolean justificationPending ( ) { return justificationPending; }
		boolean justified ( ) { return justified; }
	}
}
