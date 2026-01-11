/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.automation;

import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;

/**
 * Provides the execution context for automation handlers.
 * <p>
 * This interface exposes the capabilities available to an {@link Automation} when processing
 * todo items. Automations can execute commands and provide events to the system through
 * this context.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events that can be published
 */
public interface AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends CommandExecutionCapability<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE>, ProvidedEventCapability<DOMAIN_EVENT_TYPE> {

	// TODO should we allow ProvidedEventsCapability? or only CommandExecution?  also Aggregates?

}