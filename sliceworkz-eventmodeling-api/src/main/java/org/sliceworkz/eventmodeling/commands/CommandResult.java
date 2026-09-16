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

import org.sliceworkz.eventstore.events.Tags;

public interface CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags );

	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags, String idempotencyKey );

	/*
	 * A command-level key -- one the caller provides to execute(command, key), or one of the methods
	 * below sets -- is applied to the events the command raised when it is persisted. The store holds a
	 * key per event, scoped to the stream, and refuses a batch repeating one, so a command raising one
	 * event keeps the key as it is, and a command raising several gets <key>/1, <key>/2, ... in raise
	 * order for every event not keyed through raiseEvent(event, tags, key) itself. A retry then finds
	 * every key stored and is swallowed whole (Optional.empty()), and a re-execution under the same key
	 * that raises a different set of events is refused as IdempotencyKeyConflictException with nothing
	 * stored. The derivation is by position, so a command whose event count varies under one key should
	 * key its events itself, from what each event is about.
	 */

	/**
	 * Requires that an idempotency key was externally provided by the caller.
	 * Throws {@link IllegalStateException} if no external key was provided.
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> requireIdempotencyKey ( );

	/**
	 * Sets an idempotency key for this command, ignoring any externally provided key.
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> idempotencyKey ( String key );

	/**
	 * Sets a fallback idempotency key for this command. If the caller also provided
	 * an external key, the external key takes precedence.
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> fallbackIdempotencyKey ( String key );

	/**
	 * Sets an idempotency key for this command. Throws {@link IllegalStateException}
	 * if the caller also provided an external key.
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> exclusiveIdempotencyKey ( String key );

	/**
	 * Forbids an externally provided idempotency key. Throws {@link IllegalStateException}
	 * if the caller provided an external key.
	 * <p>
	 * For an {@link OutboundCommand} this is also the deliberate opt-out from the rule that every
	 * outbound event must carry an idempotency key: a command that calls this declares that it
	 * publishes without de-duplication on purpose, and accepts that an at-least-once caller may
	 * publish twice. Anywhere that would matter, key the events instead.
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> forbidIdempotencyKey ( );

}
