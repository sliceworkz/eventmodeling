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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the event type a component was declared over, so that a component registered on the wrong
 * bounded context can be named at {@code build()}.
 * <p>
 * Every registration on {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder} is
 * wildcard-typed — {@code readmodel(Class<? extends ReadModel<?>>)},
 * {@code automation(Automation<?,?,?>)} and the rest — so the compiler admits a component of another
 * context's event types. It cannot do otherwise: the builder is typed by the context alone
 * ({@code BoundedContextBuilder<C>}), Java has no way to project {@code D}, {@code I} and {@code O}
 * back out of {@code C extends BoundedContext<D,I,O>}, and the alternative — carrying all four as
 * type parameters, {@code BoundedContextBuilder<C,D,I,O>} — loses because that quartet then has to be
 * spelled out in every {@code Slice} signature a user writes, for a check the builder can make itself
 * from what {@code newBuilder} already resolved reflectively.
 * <p>
 * What it resolves is the actual type argument at {@code index} of {@code declaringInterface}, as the
 * component's own class implements it, substituting the type variables bound along the way — so a
 * read model extending {@code PublishingReadModel<BankingEvent,...>} answers {@code BankingEvent}
 * just as one implementing {@code ReadModel<BankingEvent>} directly does.
 */
final class EventTypeArguments {

	private EventTypeArguments ( ) {
	}

	/**
	 * The event class a component's declaration fixes, or {@code null} where it fixes none — a raw
	 * implementation, a type variable no declaration on the path binds, or a wildcard. Nothing is
	 * rejected on a null: a declaration that does not name an event type is not evidence of the wrong
	 * one.
	 */
	static Class<?> of ( Class<?> componentClass, Class<?> declaringInterface, int index ) {
		Type argument = argument(componentClass, declaringInterface, index, Map.of());
		return argument instanceof Class<?> clazz ? clazz : null;
	}

	private static Type argument ( Type type, Class<?> target, int index, Map<TypeVariable<?>,Type> bindings ) {
		if ( type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw ) {
			TypeVariable<?>[] variables = raw.getTypeParameters();
			Type[] arguments = parameterized.getActualTypeArguments();
			Map<TypeVariable<?>,Type> bound = new HashMap<>();
			for ( int i = 0; i < variables.length; i++ ) {
				bound.put(variables[i], bindings.getOrDefault(arguments[i], arguments[i]));
			}
			if ( raw == target ) {
				Type argument = bound.get(variables[index]);
				return argument instanceof TypeVariable<?> ? null : argument;
			}
			return argumentOfSupertypes(raw, target, index, bound);
		}
		if ( type instanceof Class<?> clazz && clazz != target ) {
			return argumentOfSupertypes(clazz, target, index, Map.of());
		}
		return null;
	}

	private static Type argumentOfSupertypes ( Class<?> clazz, Class<?> target, int index, Map<TypeVariable<?>,Type> bindings ) {
		for ( Type supertype : clazz.getGenericInterfaces() ) {
			Type argument = argument(supertype, target, index, bindings);
			if ( argument != null ) {
				return argument;
			}
		}
		Type superclass = clazz.getGenericSuperclass();
		return superclass == null ? null : argument(superclass, target, index, bindings);
	}

}
