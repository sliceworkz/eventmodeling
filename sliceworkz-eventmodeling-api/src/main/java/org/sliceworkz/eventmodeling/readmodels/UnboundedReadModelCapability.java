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
package org.sliceworkz.eventmodeling.readmodels;

import org.sliceworkz.eventmodeling.events.Tracing;

/**
 * Capability for reading models across all event streams, regardless of bounded context boundaries.
 * <p>
 * This is intended for internal dashboard and monitoring use only.
 * Application developers should use {@link ReadModelCapability#read} instead,
 * which scopes queries to the bounded context's own domain event stream.
 * <p>
 * It is deliberately not part of {@link org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities},
 * so an ordinary bounded context does not carry it. To use it, declare a context interface that
 * names it alongside {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContext} — the handle
 * {@code build()} returns is a proxy over exactly the interface it was asked for, so what that
 * interface names is what the caller gets:
 * <pre>{@code
 * interface Dashboard extends BoundedContext<MyEvent, MyInbound, MyOutbound>,
 *                             UnboundedReadModelCapability<MyEvent> { }
 * }</pre>
 * The same mechanism narrows in the other direction; see {@code WHO-MAY-DO-WHAT.md}.
 */
public interface UnboundedReadModelCapability<DOMAIN_EVENT_TYPE> {

	<READ_MODEL extends ReadModel<? extends DOMAIN_EVENT_TYPE>> READ_MODEL readUnbounded ( Class<READ_MODEL> readModelClass, Tracing tracing, Object... params );

	<READ_MODEL extends ReadModel<? extends DOMAIN_EVENT_TYPE>> READ_MODEL readUnbounded ( Class<READ_MODEL> readModelClass, Object... params );

}
