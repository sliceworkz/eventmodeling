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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ServiceLoader;

import org.sliceworkz.eventmodeling.EventTypes;

public interface BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>, EventTypes<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Returns the name of this bounded context.
	 * <p>
	 * The name is used for identifying the bounded context in logging, metrics,
	 * and event stream organization.
	 *
	 * @return the bounded context name
	 */
	String name ( );

	/**
	 * Creates a new builder for a bounded context type.
	 * <p>
	 * The domain, inbound, and outbound event types are resolved from the
	 * type parameters of the {@code EventTypes} superinterface.
	 *
	 * @param <C> the bounded context type
	 * @param contextType the class extending {@link BoundedContext}
	 * @return a new builder
	 */
	@SuppressWarnings("unchecked")
	public static <C extends BoundedContext<?,?,?>> BoundedContextBuilder<C> newBuilder(Class<C> contextType) {
		Type[] typeArgs = resolveEventTypes(contextType);
		if (typeArgs == null) {
			throw new IllegalArgumentException(
				"Cannot resolve EventTypes<D,I,O> type arguments from " + contextType.getName() +
				". Ensure it extends EventTypes with concrete type arguments.");
		}
		BoundedContextBuilder<C> result = ServiceLoader.load(BoundedContextBuilder.class).findFirst().get();
		result.contextType(contextType);
		result.eventTypes((Class<?>) typeArgs[0], (Class<?>) typeArgs[1], (Class<?>) typeArgs[2]);
		return result;
	}

	private static Type[] resolveEventTypes(Class<?> contextType) {
		for (Type iface : contextType.getGenericInterfaces()) {
			if (iface instanceof ParameterizedType pt) {
				if (EventTypes.class.isAssignableFrom((Class<?>) pt.getRawType())) {
					Type[] args = pt.getActualTypeArguments();
					if (allConcreteTypes(args)) return args;
				}
			}
		}
		// Search recursively through super-interfaces
		for (Type iface : contextType.getGenericInterfaces()) {
			Class<?> rawType = iface instanceof ParameterizedType pt
				? (Class<?>) pt.getRawType()
				: (Class<?>) iface;
			if (EventTypes.class.isAssignableFrom(rawType)) {
				Type[] result = resolveEventTypes(rawType);
				if (result != null) return result;
			}
		}
		return null;
	}

	private static boolean allConcreteTypes(Type[] types) {
		for (Type t : types) {
			if (!(t instanceof Class)) return false;
		}
		return true;
	}

}
