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
 * A command that synchronously returns a response value after successful execution
 * and event persistence.
 * <p>
 * Unlike {@link Command}, which returns {@code void} via {@link Command#execute},
 * this interface allows the command to compute a response value during execution (e.g., a
 * generated ID) that is delivered to the caller only after events have been successfully
 * persisted.
 * <p>
 * The command should use the {@link CommandContext} to set up decision models and raise
 * events as usual, and return the response value from the {@code execute} method.
 * <p>
 * <strong>This is the one command shape whose choice of decision models cannot be enforced by the
 * compiler.</strong> {@link Command#execute} and {@link OutboundCommand#execute} return the
 * {@link CommandResult}, which is obtainable only from {@code decisionModels(...)} or
 * {@code noDecisionModels()} — so those two cannot be written without choosing. Here the return slot
 * is taken by the caller's response value, and a command returning both would be returning a pair
 * whose halves answer to different readers. So a {@code CommandWithResult} that chooses neither is
 * still found out on its first execution, with an {@link IllegalStateException} naming the command and
 * the call missing from it. Choose, then compute the response.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <RESPONSE_TYPE> the type of the response value returned to the caller
 */
public interface CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> {

	default String commandName ( ) {
		return AbstractCommand.commandNameOf(this.getClass());
	}

	/**
	 * Decides, raises what it decided on, and computes the response.
	 * <p>
	 * The decision is made through the context as in any other command —
	 * {@code context.decisionModels(...)} or {@code context.noDecisionModels()} — and a command that
	 * does neither fails at execution rather than at compile time, since this method's return value is
	 * the response rather than the {@link CommandResult}.
	 *
	 * @param context the execution context
	 * @return the response value, delivered to the caller after the raised events are persisted
	 */
	RESPONSE_TYPE execute ( CommandContext<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> context );

}
