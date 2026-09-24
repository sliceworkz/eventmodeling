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
/**
 * How a bounded context reports what it does, for an application to bind to the metrics or tracing
 * library it uses: {@link org.sliceworkz.eventmodeling.observability.BoundedContextObserver}, the sealed
 * {@link org.sliceworkz.eventmodeling.observability.Observation} kinds it is told about and the
 * {@link org.sliceworkz.eventmodeling.observability.Outcome}s they complete with.
 * <p>
 * The counterpart of the event store's {@code org.sliceworkz.eventstore.observability}, one layer up and
 * shaped the same way. No module of the framework depends on a metrics library.
 */
package org.sliceworkz.eventmodeling.observability;
