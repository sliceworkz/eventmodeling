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

import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public interface CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags );

	/**
	 * Raises an event tagged with the given tags, made elsewhere — an entity's
	 * ({@code ACCOUNT.tag(accountId)}), a rule tag — without wrapping them in {@link Tags#of(Tag...)}
	 * first: {@code raiseEvent(new AccountOpened(...), ACCOUNT.tag(accountId), CUSTOMER.tag(customerId))}.
	 * The same as {@link #raiseEvent(Object, Tags)} with {@code Tags.of(tags)}.
	 */
	public default CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tag... tags ) {
		return raiseEvent(event, Tags.of(tags));
	}

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
	 * How a key the caller executes the command under relates to the command's own. Without a call to
	 * either {@code idempotencyKey} method the caller's key, if it gives one, is the key, and the command has
	 * none of its own.
	 * <table>
	 * <caption>the five choices</caption>
	 * <tr><th>call</th><th>no key from the caller</th><th>a key from the caller</th></tr>
	 * <tr><td>{@code idempotencyKey(CallerKey.REQUIRED)}</td><td>refused</td><td>the caller's</td></tr>
	 * <tr><td>{@code idempotencyKey(CallerKey.REFUSED)}</td><td>none</td><td>refused</td></tr>
	 * <tr><td>{@code idempotencyKey(key, CallerKey.PREFERRED)}</td><td>the command's</td><td>the caller's</td></tr>
	 * <tr><td>{@code idempotencyKey(key, CallerKey.IGNORED)}</td><td>the command's</td><td>the command's</td></tr>
	 * <tr><td>{@code idempotencyKey(key, CallerKey.REFUSED)}</td><td>the command's</td><td>refused</td></tr>
	 * </table>
	 * A refusal is an {@link IllegalStateException}, before anything is appended: the command and its
	 * caller disagree about who keys the execution, which is a bug in one of them.
	 * <p>
	 * The alternative — a method per choice ({@code requireIdempotencyKey}, {@code fallbackIdempotencyKey},
	 * {@code exclusiveIdempotencyKey}, …) — loses because the five read as five unrelated features, where
	 * they are one question asked of one key.
	 */
	enum CallerKey {
		/** The caller must give a key, and it is the key; the command has none of its own. */
		REQUIRED,
		/** The caller must not give a key. */
		REFUSED,
		/** The caller's key wins over the command's; the command's is used when the caller gives none. */
		PREFERRED,
		/** The command's key is the key, whatever the caller gives. */
		IGNORED
	}

	/**
	 * Keys this execution with the command's own key, and says how a caller's key relates to it:
	 * {@link CallerKey#PREFERRED}, {@link CallerKey#IGNORED} or {@link CallerKey#REFUSED}.
	 *
	 * @throws IllegalArgumentException for a {@code null} or blank key, or {@link CallerKey#REQUIRED}, which
	 *         leaves no use for the command's key
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> idempotencyKey ( String key, CallerKey callerKey );

	/**
	 * Says what a caller's key must be for a command with no key of its own:
	 * {@link CallerKey#REQUIRED} or {@link CallerKey#REFUSED}.
	 *
	 * @throws IllegalArgumentException for {@link CallerKey#PREFERRED} or {@link CallerKey#IGNORED}, which
	 *         need a key of the command's own
	 */
	public CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> idempotencyKey ( CallerKey callerKey );

}
