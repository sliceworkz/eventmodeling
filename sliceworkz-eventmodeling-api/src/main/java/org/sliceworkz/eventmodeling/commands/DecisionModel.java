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

import org.sliceworkz.eventstore.projection.Projection;

/**
 * A projection a command instantiates inline to decide on: {@code eventQuery()} scoped by tags to the
 * entity, {@code when(...)} folding the state, accessors for the rules to check.
 * <p>
 * What makes it more than a lookup is that {@code context.decisionModels(...)} puts the events it was
 * projected from inside the command's consistency boundary. Every read is bounded at the domain
 * stream's head, that head is the append's expected reference, and the optimistic-lock filter is the
 * union of <em>every</em> query the models were read with &mdash; each {@code eventQuery()}, and the
 * {@code initQuery()} of a model using the savepoint pattern. So the DCB check at append time re-asks
 * exactly the question the command decided on, and an event landing in between raises
 * {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}.
 * <p>
 * Two consequences worth knowing:
 * <ul>
 * <li><b>The savepoint types stay out of {@code eventQuery()}.</b> The savepoint pattern asks for the
 * two queries to name disjoint event types, or the savepoint is double-processed &mdash; and nothing
 * is given up by that: "the newest savepoint is X" is a fact the command decided on, so the framework
 * locks on {@code initQuery()} too, whether or not a savepoint was found.</li>
 * <li><b>An {@code until} on a model's own query bounds its read and nothing else.</b> It is stripped
 * from the lock filter, since a criteria filter carrying one deems no event after it a new relevant
 * fact and would admit every append without ever raising.</li>
 * </ul>
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type of the bounded context
 */
public interface DecisionModel<DOMAIN_EVENT_TYPE> extends Projection<DOMAIN_EVENT_TYPE> {

}
