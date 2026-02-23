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

import org.sliceworkz.eventstore.query.EventQuery;

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
	 * Returns the event query that determines which inbound events this translator handles.
	 * <p>
	 * The query is used to filter the inbound event stream and only deliver relevant events
	 * to this translator.
	 *
	 * @return the event query for filtering inbound events
	 */
	EventQuery eventQuery();

	/**
	 * Translates an inbound event into domain events or commands.
	 * <p>
	 * The translator processes the inbound event and uses the supplied context to execute
	 * commands or provide domain events. Translation should be idempotent to handle retries.
	 *
	 * @param event the inbound event to translate
	 * @param context the translator context providing command execution and event capabilities
	 */
	void translate ( INBOUND_EVENT_TYPE event, TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> context );

}
