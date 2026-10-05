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
package org.sliceworkz.eventmodeling.examples.banking.features.freezeaccount;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountAccess.AccountFrozen;
import org.sliceworkz.eventmodeling.examples.banking.features.accountstanding.AccountStandingDecisionModel;

/**
 * Freezes an open account. Its cards are blocked afterwards, by {@code BlockCardsOfAccountPolicy}: this command
 * records the fact and nothing more.
 *
 * @param accountId the account
 * @param reason why, as the bank records it
 */
public record FreezeAccountCommand ( AccountId accountId, String reason ) implements Command<BankingEvent> {

	public FreezeAccountCommand {
		if ( accountId == null ) {
			throw new IllegalArgumentException("a freeze names the account it freezes");
		}
		if ( reason == null || reason.isBlank() ) {
			throw new IllegalArgumentException("a freeze needs a reason");
		}
	}

	@Override
	public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
		var account = new AccountStandingDecisionModel(accountId);
		var result = context.decisionModels(account);

		BusinessException.when(!account.opened(), "Account " + accountId.value() + " does not exist");
		BusinessException.when(account.closed(), "Account " + accountId.value() + " is closed");
		BusinessException.when(account.frozen(), "Account " + accountId.value() + " is already frozen");

		result.raiseEvent(new AccountFrozen(accountId, reason.strip()), BankingDomainWithClosingTheBooks.ACCOUNT.tags(accountId));
	}

}
