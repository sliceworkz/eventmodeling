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
 * @param <C> the bounded context type
 */
public interface AdapterBinding<C extends BoundedContext<?,?,?>> {

	/**
	 * Binds the adapter to the specified port type with the default (unqualified) qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class that the adapter implements
	 * @return the builder for continued chaining
	 * @throws IllegalArgumentException if the adapter is not assignable to the port type
	 */
	<T> BoundedContextBuilder<C> forPort(Class<T> portType);

	/**
	 * Binds the adapter to the specified port type with a named qualification.
	 * <p>
	 * This allows multiple adapters to be registered for the same port type
	 * under different qualifications.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class that the adapter implements
	 * @param qualification the qualification name
	 * @return the builder for continued chaining
	 * @throws IllegalArgumentException if the adapter is not assignable to the port type
	 */
	<T> BoundedContextBuilder<C> forPort(Class<T> portType, String qualification);

}
