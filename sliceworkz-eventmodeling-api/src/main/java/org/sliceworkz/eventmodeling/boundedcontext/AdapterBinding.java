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
package org.sliceworkz.eventmodeling.boundedcontext;

/**
 * Intermediate builder step for binding an adapter to a port.
 * <p>
 * Created by {@link BoundedContextBuilder#adapter(Object)} and completed
 * by calling {@link #forPort(Class)}.
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event root type
 * @param <INBOUND_EVENT_TYPE> the inbound event root type
 * @param <OUTBOUND_EVENT_TYPE> the outbound event root type
 */
public interface AdapterBinding<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Binds the adapter to the specified port type with the default (unqualified) qualification.
	 * <p>
	 * The adapter must be assignable to the port type.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class that the adapter implements
	 * @return a qualifiable binding that can optionally be further qualified, or chained as a builder
	 * @throws IllegalArgumentException if the adapter is not assignable to the port type
	 */
	<T> QualifiableAdapterBinding<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> forPort(Class<T> portType);

}
