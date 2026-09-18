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
package org.sliceworkz.eventmodeling.events;

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Recording a domain event that no command raised.
 * <p>
 * An event provided this way is appended to the domain stream as it stands: no decision model is
 * read, no consistency boundary is pinned and none is checked on append, so nothing here can raise
 * an {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}. That is the whole
 * difference from {@link org.sliceworkz.eventmodeling.commands.CommandExecutionCapability#execute
 * executing a command}, and it is why this is a narrow escape hatch rather than a second way to
 * write: use it where the fact is already settled by the time it reaches the context — a CRUD
 * front-end recording what it has stored elsewhere, an import, a migration — and use a command
 * everywhere a rule has to be judged against history.
 * <p>
 * The idempotency-key overloads are what make it safe to repeat. The key is scoped to the stream,
 * and a repeat is silently ignored by storage, which surfaces as an empty {@code Optional} — the
 * same value as &quot;not appended&quot;, deliberately not distinguished. Derive the key from the
 * fact being recorded, never from the attempt, or a replay mints a fresh key and de-duplicates
 * nothing.
 * <p>
 * Automations and translators reach this through their own context, where it is the ordinary way to
 * record what a handled item produced. On a bounded context it is deliberately left off
 * {@link org.sliceworkz.eventmodeling.boundedcontext.ApplicationCapabilities}, so application code
 * opts into it by naming this interface rather than inheriting it with everything else.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface ProvidedEventCapability<DOMAIN_EVENT_TYPE> {

	/**
	 * Provides a domain event with tracing information.
	 *
	 * @param event the domain event to provide
	 * @param tracing the tracing information for distributed tracing
	 * @return reference to the appended event, or empty if the event was not appended
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tracing tracing );

	/**
	 * Provides a domain event without tracing information.
	 *
	 * @param event the domain event to provide
	 * @return reference to the appended event, or empty if the event was not appended
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event );

	/**
	 * Provides a domain event with tags for categorization and filtering.
	 *
	 * @param event the domain event to provide
	 * @param tags the tags to associate with the event
	 * @return reference to the appended event, or empty if the event was not appended
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags );

	/**
	 * Provides a domain event with both tags and tracing information.
	 *
	 * @param event the domain event to provide
	 * @param tags the tags to associate with the event
	 * @param tracing the tracing information for distributed tracing
	 * @return reference to the appended event, or empty if the event was not appended
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags, Tracing tracing );

	/**
	 * Provides a domain event under an idempotency key, so that providing it again stores nothing.
	 * <p>
	 * The key is scoped to the event stream, and a second event carrying a key already used on that
	 * stream is silently ignored by storage. This is what makes an at-least-once caller safe: an
	 * automation is handed the same todo item again whenever the events it raised have not reached its
	 * todo list — after a crash between the append and the bookmark, and after every failure — and the
	 * key is what keeps that from appending the same fact twice. Derive it from the item rather than
	 * from the attempt, or every retry gets a fresh key and dedups nothing.
	 * <p>
	 * <strong>An empty return means the event was already there</strong>, not that nothing happened: it
	 * is the same {@code Optional.empty()} a caller gets for an event that was not appended, and the
	 * two are not distinguished. For an automation that is the right outcome either way — the work is
	 * done, and the todo list drops the item once the original event is projected.
	 *
	 * @param event the domain event to provide
	 * @param idempotencyKey the key identifying this event on its stream, or null for no idempotency check
	 * @return reference to the appended event, or empty if it was not appended, including when the key was already used
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, String idempotencyKey );

	/**
	 * Provides a domain event with tags, under an idempotency key.
	 *
	 * @param event the domain event to provide
	 * @param tags the tags to associate with the event
	 * @param idempotencyKey the key identifying this event on its stream, or null for no idempotency check
	 * @return reference to the appended event, or empty if it was not appended, including when the key was already used
	 * @see #event(Object, String)
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags, String idempotencyKey );

	/**
	 * Provides a domain event with tags and tracing information, under an idempotency key.
	 *
	 * @param event the domain event to provide
	 * @param tags the tags to associate with the event
	 * @param idempotencyKey the key identifying this event on its stream, or null for no idempotency check
	 * @param tracing the tracing information for distributed tracing
	 * @return reference to the appended event, or empty if it was not appended, including when the key was already used
	 * @see #event(Object, String)
	 */
	Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags, String idempotencyKey, Tracing tracing );

}
