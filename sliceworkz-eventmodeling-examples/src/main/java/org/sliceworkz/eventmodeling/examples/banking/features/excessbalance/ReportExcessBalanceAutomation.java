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

import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.features.excessbalance.DepositsAboveGuaranteeTodoList.ExcessBalanceToReport;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Carries out, afterwards, the enforcement of {@code balance-within-guarantee} that every deposit taking an
 * account over the guarantee deferred:
 * <pre>
 *   DepositCommand → MoneyDeposited [x-rule-deferred] → DepositsAboveGuaranteeTodoList
 *                  → ReportExcessBalanceAutomation → ReportExcessBalanceCommand → ExcessBalanceReported
 * </pre>
 * This is the whole of what deferred enforcement means in an event-sourced system: the rule is not enforced
 * <em>now</em>, the violation is a recorded fact, and an ordinary automation enforces it once it can. Nothing
 * about it is special to the rulebook — the automation would look the same if the todo list were fed by any
 * other fact.
 * <p>
 * The items are independent of one another, but the default failure action ({@code RETRY_ITEM}) is kept: a
 * report that fails is almost always the store being unavailable, which would fail every item behind it too.
 */
public class ReportExcessBalanceAutomation implements Automation<ExcessBalanceToReport, BankingEvent, BankingOutboundEvent> {

	private final DepositsAboveGuaranteeTodoList todoList;

	public ReportExcessBalanceAutomation ( DepositsAboveGuaranteeTodoList todoList ) {
		this.todoList = todoList;
	}

	@Override
	public TodoListReadModel<BankingEvent, ExcessBalanceToReport> getTodoList ( ) {
		return todoList;
	}

	@Override
	public Optional<EventReference> handle ( ExcessBalanceToReport item, AutomationContext<BankingEvent, BankingOutboundEvent> context ) {
		return context.execute(new ReportExcessBalanceCommand(item.accountId(), item.deposit()));
	}
}
