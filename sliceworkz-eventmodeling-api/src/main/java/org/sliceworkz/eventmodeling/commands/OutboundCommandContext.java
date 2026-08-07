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

import org.sliceworkz.eventmodeling.readmodels.ReadModel;

/**
 * The execution context handed to an {@link OutboundCommand} — deliberately narrower than the
 * {@link CommandContext} a {@link Command} receives.
 * <p>
 * What is missing is {@code decisionModels(...)}, and that is the point: decision models cannot guard
 * an outbound append. They are projected from the domain stream, but the append criteria they produce
 * travel with the append — which runs against the <em>outbound</em> stream, where domain event types
 * never occur, so the optimistic-locking check would match nothing and admit everything. A boundary
 * that guards nothing, silently, is worse than none, so this context does not offer one: an outbound
 * command calls {@link #noDecisionModels()} and takes its correctness from its idempotency key, which
 * the framework requires on every outbound event unless the command opts out with
 * {@link CommandResult#forbidIdempotencyKey()}.
 *
 * @param <CONSUMED_EVENT_TYPE> the base type of domain events the command may read
 * @param <PRODUCED_EVENT_TYPE> the base type of events the command raises
 */
public interface OutboundCommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	/**
	 * Projects a live read model and hands it to the command, for auxiliary lookups only.
	 * <p>
	 * <strong>A read done this way is not part of any consistency boundary.</strong> The events this
	 * read model was projected from are not covered by an optimistic-locking check: one of them can be
	 * superseded between this read and the append, and the append still succeeds. So do not decide on
	 * what this returns — for a {@link Command}, anything a raised event depends on belongs in a
	 * {@link DecisionModel} passed to {@link CommandContext#decisionModels(DecisionModel...)}; for an
	 * {@link OutboundCommand}, whose append no decision model can guard anyway, correctness comes from
	 * the idempotency key.
	 */
	<T> T read ( Class<? extends ReadModel<? extends CONSUMED_EVENT_TYPE>> readModelClass, Object... constructorParams);

	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> noDecisionModels ( );

}
