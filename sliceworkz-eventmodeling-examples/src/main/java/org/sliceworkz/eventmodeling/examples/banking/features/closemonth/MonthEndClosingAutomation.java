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
package org.sliceworkz.eventmodeling.examples.banking.features.closemonth;

import java.time.YearMonth;
import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.features.closemonth.AccountsToCloseTodoList.AccountToClose;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Automation that processes month-end closings for all accounts.
 * <p>
 * Follows the Event Modeling automation pattern:
 * <pre>
 *   Events → TodoList (which accounts need closing?) → Automation → CloseMonthCommand → Events
 * </pre>
 *
 * <p>The automation is triggered when:
 * <ol>
 *   <li>A scheduler publishes a "month end reached" trigger</li>
 *   <li>The TodoList identifies accounts with an active month matching the closing month</li>
 *   <li>For each account, this automation executes a {@link CloseMonthCommand}</li>
 *   <li>The command emits MonthClosed + MonthOpened events</li>
 *   <li>The TodoList updates — the account no longer needs closing</li>
 * </ol>
 *
 * <p>Idempotency is handled by the {@link CloseMonthCommand}'s idempotency keys
 * on the raised events. If the automation retries (e.g., after a crash), the
 * idempotency keys prevent duplicate MonthClosed/MonthOpened events.</p>
 */
public class MonthEndClosingAutomation
		implements Automation<AccountToClose, BankingEvent, BankingOutboundEvent> {

	private final YearMonth monthToClose;

	public MonthEndClosingAutomation(YearMonth monthToClose) {
		this.monthToClose = monthToClose;
	}

	@Override
	public TodoListReadModel<BankingEvent, AccountToClose> getTodoList() {
		return new AccountsToCloseTodoList(monthToClose);
	}

	@Override
	public Optional<EventReference> handle(
			AccountToClose todoItem,
			AutomationContext<BankingEvent, BankingOutboundEvent> context) {

		// Execute the close command for this account
		return context.execute(
			new CloseMonthCommand(todoItem.accountId(), todoItem.monthToClose())
		);
	}
}
