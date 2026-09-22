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
package org.sliceworkz.eventmodeling.commands;

/**
 * A command that decides on domain events and raises domain events.
 *
 * <p><strong>{@code execute} returns the {@link CommandResult}, and that return type is the contract.</strong>
 * A {@code CommandResult} can only be obtained by choosing what the command decides on —
 * {@link CommandContext#decisionModels(DecisionModel...)}, or
 * {@link OutboundCommandContext#noDecisionModels()} when it decides on nothing — so a command that
 * compiles has made that choice. That is the whole reason the method does not return {@code void}: the
 * choice is what pins the consistency boundary and produces the object events are raised on, so a
 * command that skips it has nothing to append and no boundary to append under — a mistake that a
 * {@code void} signature leaves for the first execution to find.
 *
 * <p>The framework takes the result from the context rather than from the return value — all three
 * command shapes are read the same way there, and a command that decided and raised its events but
 * returned something else has still done its job. So what is returned is not otherwise used; returning
 * it is what makes the call impossible to forget. A runtime check remains as the backstop for the two
 * ways left to skip the choice: a {@code Command} body that returns {@code null}, and a
 * {@link CommandWithResult}, whose return slot is taken by the caller's response.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public non-sealed interface Command<DOMAIN_EVENT_TYPE> extends AbstractCommand<DOMAIN_EVENT_TYPE,DOMAIN_EVENT_TYPE> {

	/**
	 * Decides, and raises what it decided on.
	 *
	 * @param context the execution context
	 * @return the result the command decided on and raised its events through — {@code context.decisionModels(...)}
	 *         or {@code context.noDecisionModels()}, after whatever was chained onto it
	 */
	CommandResult<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> execute ( CommandContext<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> context );

}
