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

import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;

/**
 * Provides the execution context for translator handlers.
 * <p>
 * This interface exposes the capabilities available to a {@link Translator} when processing
 * inbound events. Translators can execute commands and provide domain events to the system
 * through this context.
 *
 * @param <INBOUND_EVENT_TYPE> the base type of inbound events received from external systems
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface TranslatorContext<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> extends ProvidedEventCapability<DOMAIN_EVENT_TYPE>, CommandExecutionCapability<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> {

}
