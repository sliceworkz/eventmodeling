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
 * A command that raises <em>outbound</em> events — the only writer the outbound stream has, and
 * therefore the one way to put something in front of the dispatchers.
 * <p>
 * Like every command it appends to exactly one stream, so it raises outbound events <strong>only</strong>:
 * there is no command shape that raises a domain event and an outbound event together, because the two
 * live on separate streams whose appends commit independently. Where both are needed — record the fact
 * as a domain event <em>and</em> publish it — that is an automation pattern, composed in
 * {@code Automation.handle}: execute the {@code OutboundCommand} first, then provide the domain event,
 * each under an idempotency key derived from the todo item. The order is load-bearing (only the domain
 * event completes the todo item, so outbound-first makes a crash between the two a retry that
 * de-duplicates; the other order loses the publication for good). See the project documentation for the
 * full reasoning.
 * <p>
 * <strong>Decision models do not guard an outbound append.</strong> They are projected from the domain
 * stream, but the append criteria they produce are checked against the <em>outbound</em> stream, where
 * domain event types never occur — the optimistic-locking check matches nothing and admits everything.
 * An {@code OutboundCommand} should call {@code context.noDecisionModels()} and take its correctness
 * from an idempotency key instead. And since the callers that execute one (automations above all) are
 * at-least-once, an {@code OutboundCommand} without an idempotency key is a duplicate publication
 * waiting for its first retry — derive the key from the work item, never from the attempt.
 */
public non-sealed interface OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends AbstractCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {


}
