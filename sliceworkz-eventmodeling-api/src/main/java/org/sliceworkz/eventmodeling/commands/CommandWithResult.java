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
 * Unlike {@link Command}, which returns {@code void} via {@link AbstractCommand#execute},
 * this interface allows the command to compute a response value during execution (e.g., a
 * generated ID) that is delivered to the caller only after events have been successfully
 * persisted.
 * <p>
 * The command should use the {@link CommandContext} to set up decision models and raise
 * events as usual, and return the response value from the {@code execute} method.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <RESPONSE_TYPE> the type of the response value returned to the caller
 */
public interface CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> {

	default String commandName ( ) {
		return AbstractCommand.commandNameOf(this.getClass());
	}

	RESPONSE_TYPE execute ( CommandContext<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> context );

}
