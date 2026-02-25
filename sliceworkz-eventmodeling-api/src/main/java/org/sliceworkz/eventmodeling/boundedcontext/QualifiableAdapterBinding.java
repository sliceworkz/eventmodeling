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
 * Builder step that optionally qualifies an adapter binding and continues the builder chain.
 * <p>
 * After {@link AdapterBinding#forPort(Class)}, the binding is registered with the default
 * qualification. Calling {@link #withQualification(String)} replaces the default qualification
 * with a named one, allowing multiple adapters for the same port type.
 * <p>
 * This interface extends {@link BoundedContextBuilder} so the chain can continue fluently
 * without calling an extra method.
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event root type
 * @param <INBOUND_EVENT_TYPE> the inbound event root type
 * @param <OUTBOUND_EVENT_TYPE> the outbound event root type
 */
public interface QualifiableAdapterBinding<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>
		extends BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Qualifies this adapter binding with the given name.
	 * <p>
	 * This replaces the default qualification, allowing multiple adapters to be registered
	 * for the same port type under different qualifications.
	 * <p>
	 * Example:
	 * <pre>
	 *   .adapter(inMemoryCache).forPort(Cache.class)                             // default
	 *   .adapter(customerCache).forPort(Cache.class).withQualification("customers")  // named
	 * </pre>
	 *
	 * @param qualification the qualification name
	 * @return the builder for continued chaining
	 */
	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> withQualification(String qualification);

}
