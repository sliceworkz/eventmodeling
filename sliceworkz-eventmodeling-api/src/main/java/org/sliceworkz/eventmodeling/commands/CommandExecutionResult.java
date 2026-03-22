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
 * The result of executing a {@link CommandWithResult}, containing both the event reference
 * (if events were persisted) and the synchronous response value computed by the command.
 *
 * @param <RESPONSE_TYPE> the type of the response value
 * @param eventReference reference to the last persisted event, or empty if no events were raised
 * @param response the response value computed by the command during execution
 */
public record CommandExecutionResult<RESPONSE_TYPE> ( Optional<EventReference> eventReference, RESPONSE_TYPE response ) {

}
