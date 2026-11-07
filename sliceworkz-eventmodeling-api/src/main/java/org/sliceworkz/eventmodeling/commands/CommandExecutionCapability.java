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

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Executing commands. A command is executed once with {@link #execute(Command)}, and otherwise with an
 * {@link Execution} saying how: under an idempotency key, re-executed on a conflict, or both.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 */
public interface CommandExecutionCapability<DOMAIN_EVENT_TYPE> {

	/**
	 * Executes the command once, with no idempotency key: {@code execute(command, Execution.ONCE)}.
	 */
	default Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command ) {
		return execute(command, Execution.ONCE);
	}

	/**
	 * Executes the command as the {@link Execution} says.
	 *
	 * @return the reference of the last event appended; empty when nothing was appended, including an
	 *         execution whose every idempotency key was stored before
	 */
	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Execution execution );

	/**
	 * Executes the command once, with no idempotency key: {@code execute(command, Execution.ONCE)}.
	 */
	default <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command ) {
		return execute(command, Execution.ONCE);
	}

	/**
	 * Executes the command as the {@link Execution} says; on a retry, the response is the one the
	 * successful attempt computed.
	 */
	<RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Execution execution );

}
