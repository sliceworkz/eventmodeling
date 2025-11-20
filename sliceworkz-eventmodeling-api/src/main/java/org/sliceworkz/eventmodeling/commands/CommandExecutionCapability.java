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
package org.sliceworkz.eventmodeling.commands;

import java.util.Optional;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EventReference;

public interface CommandExecutionCapability<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command );

	Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tx );

	Optional<EventReference>  execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command );

	Optional<EventReference>  execute ( OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tx );

}
