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
package org.sliceworkz.eventmodeling.inbound;

import java.util.List;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EventReference;

public interface TranslationCapability<INBOUND_EVENT_TYPE> {

	void incoming ( INBOUND_EVENT_TYPE event );

	void incoming ( INBOUND_EVENT_TYPE event, Tracing tracing );

	void incoming ( INBOUND_EVENT_TYPE event, String idempotencyKey );

	void incoming ( INBOUND_EVENT_TYPE event, String idempotencyKey, Tracing tracing );

	/**
	 * Interactively translates an inbound event.
	 * <p>
	 * In contrast to {@link #incoming(Object)} - which appends the inbound event to the inbound
	 * stream and lets the registered {@link Translator}s pick it up eventually (and asynchronously) -
	 * this method runs the translation synchronously in the calling thread. Every registered
	 * translator whose {@link Translator#eventQuery()} matches the supplied event is invoked, reusing
	 * the exact same {@link Translator} implementations registered with the bounded context. The
	 * inbound event itself is <em>not</em> persisted to the inbound stream.
	 * <p>
	 * This makes some integration scenarios easier or more logical to implement, for example when a
	 * caller wants to act immediately on the domain events produced by a translation.
	 *
	 * @param event the inbound event to translate
	 * @return references to the domain events raised during translation, in the order they were raised
	 * @throws NoTranslatorRegisteredException if no registered translator matches the supplied event
	 */
	List<EventReference> translate ( INBOUND_EVENT_TYPE event );

	/**
	 * Interactively translates an inbound event with tracing information.
	 *
	 * @param event the inbound event to translate
	 * @param tracing the tracing information for distributed tracing
	 * @return references to the domain events raised during translation, in the order they were raised
	 * @throws NoTranslatorRegisteredException if no registered translator matches the supplied event
	 * @see #translate(Object)
	 */
	List<EventReference> translate ( INBOUND_EVENT_TYPE event, Tracing tracing );

}
