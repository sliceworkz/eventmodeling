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

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * Translates inbound events from external systems into domain events or commands.
 * <p>
 * Translators follow the Event Modeling translation pattern: they receive events from
 * external systems (integration events) and translate them into domain events or execute
 * commands within the bounded context.
 *
 * @param <INBOUND_EVENT_TYPE> the base type of inbound events received from external systems
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> {

	/**
	 * The inbound events this translator handles.
	 * <p>
	 * A filter and not a query: the inbound stream is always read forward from the translator's
	 * bookmark, one event after another, so a direction, a limit or a read bound would have nothing to
	 * mean. On the interactive {@code translate(...)} path the filter decides which translators an
	 * inbound event is handed to.
	 *
	 * @return the filter selecting inbound events
	 */
	EventFilter eventFilter ( );

	/**
	 * Translates an inbound event into domain events or commands.
	 * <p>
	 * The translator processes the inbound event and uses the supplied context to execute
	 * commands or provide domain events. Translation should be idempotent to handle retries: derive an
	 * idempotency key from {@code event.reference().id()} where a repeat would be wrong.
	 * <p>
	 * The inbound event is handed over as the {@link Event} it is, with its tags, timestamp and reference
	 * beside its data — the same shape every other component of the framework is handed. An event that came
	 * in through {@code incoming(...)} is the event stored on the inbound stream. One translated
	 * interactively through {@code translate(...)} is not stored anywhere: its reference carries a freshly
	 * minted id and a position that names no stored event, so it is fit to key what the translation raises
	 * and for nothing else — compare nothing against it.
	 *
	 * @param event the inbound event to translate
	 * @param context the translator context providing command execution and event capabilities
	 */
	void translate ( Event<INBOUND_EVENT_TYPE> event, TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> context );

}
