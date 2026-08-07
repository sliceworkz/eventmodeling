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
 * The execution context handed to a {@link Command} (and to a {@link CommandWithResult}).
 * <p>
 * Extends {@link OutboundCommandContext} with the one capability an {@link OutboundCommand} must not
 * have: {@link #decisionModels(DecisionModel...)}. A domain command appends to the same stream its
 * decision models are projected from, so the boundary they produce genuinely guards the append —
 * which is exactly what does not hold for an outbound append, and why the narrower context exists.
 */
public interface CommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> extends OutboundCommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> decisionModels ( @SuppressWarnings("unchecked") DecisionModel<CONSUMED_EVENT_TYPE>... decisionModels );

}
