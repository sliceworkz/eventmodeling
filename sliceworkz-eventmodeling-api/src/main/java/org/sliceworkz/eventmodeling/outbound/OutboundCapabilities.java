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
package org.sliceworkz.eventmodeling.outbound;

import org.sliceworkz.eventmodeling.readmodels.ReadModelCapability;

/**
 * What a bounded context does with its outbound stream, as {@code context.outbound()} hands it out: reading
 * the read models registered on {@code builder.outbound()} — what went out, counted per type or tag value.
 * <p>
 * Nothing here writes: the outbound stream is written by {@link Publisher}s alone, each mapping a domain event
 * a command recorded. {@code read} reads a read model over the outbound stream; a read model over the domain
 * stream is read on the context itself, and passing one here does not compile.
 *
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public interface OutboundCapabilities<OUTBOUND_EVENT_TYPE> extends ReadModelCapability<OUTBOUND_EVENT_TYPE> {

}
