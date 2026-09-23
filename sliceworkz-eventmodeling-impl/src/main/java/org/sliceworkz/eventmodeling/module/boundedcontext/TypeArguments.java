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
 * Resolves the actual type argument a class binds at a given position of an interface it implements,
 * substituting the type variables bound along the way.
 * <p>
 * Two registration checks need it, and both exist because a registration is wildcard-typed where the
 * compiler cannot be given the constraint:
 * <ul>
 *   <li>the event type a component was declared over ({@code ReadModel<?>}, {@code Automation<?,?,?>}
 *       and the rest on {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder}),
 *       against this context's own — the builder is typed by the context alone, and Java has no way
 *       to project {@code D}, {@code I} and {@code O} back out of
 *       {@code C extends BoundedContext<D,I,O>};</li>
 *   <li>the bounded context a feature slice was declared for ({@code Slice<C>}), against the context
 *       being built — package scanning hands back a {@code Class} and the cast to {@code Slice<C>} is
 *       unchecked, so two contexts sharing a root package each discover the other's slices.</li>
 * </ul>
 * Because it walks the generic superclass and superinterfaces, a read model extending
 * {@code PublishingReadModel<BankingEvent,...>} answers the same as one implementing
 * {@code ReadModel<BankingEvent>} directly.
 */
final class TypeArguments {

	private TypeArguments ( ) {
	}

	/**
	 * The class the declaration fixes at {@code index} of {@code declaringInterface}, or {@code null}
	 * where it fixes none — a raw implementation, a type variable no declaration on the path binds, or
	 * a wildcard. Both callers read a null as "says nothing" rather than as evidence: a component whose
	 * declaration names no event type is not evidence of the wrong one, and a slice whose declaration
	 * names no context is not evidence that it belongs to another.
	 */
	static Class<?> of ( Class<?> type, Class<?> declaringInterface, int index ) {
		Type argument = argument(type, declaringInterface, index, Map.of());
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
