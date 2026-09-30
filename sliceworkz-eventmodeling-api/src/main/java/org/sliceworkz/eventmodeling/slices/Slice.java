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
package org.sliceworkz.eventmodeling.slices;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

/**
 * A feature slice of the bounded context {@code C}: a class annotated {@link FeatureSlice}, discovered by
 * package scanning, that registers its components while the context is built ({@code configure...}) and
 * wires whatever runs beside them — REST endpoints, schedulers — once it is started ({@code start...}).
 * Each pair is called only for the {@link Aspect}s the deployment runs.
 * <p>
 * {@code C} is the slice's bounded context, and the only place it is said: a context deploys the slices
 * it can be handed to, so a slice declared over a sibling context is none of its business and one declared
 * over a supertype is deployed by every context that fits. What a slice is — its {@link SliceType} — is
 * derived from what it registers, never declared.
 *
 * @param <C> the bounded context this slice belongs to
 */
public interface Slice<C extends BoundedContext<?,?,?>> {

	/** The class' simple name without its {@code FeatureSlice} suffix. */
	default String name ( ) {
		String name = this.getClass().getSimpleName();
		if ( name.endsWith("FeatureSlice")) {
			name = name.substring(0, name.length() - "FeatureSlice".length());
		}
		return name;
	}

	/** The {@link FeatureSlice#chapter() chapter} the annotation declares, empty when it declares none. */
	default String chapter ( ) {
		FeatureSlice meta = meta();
		return meta == null ? "" : meta.chapter();
	}

	/** The annotation on this slice's class, {@code null} for a slice that is not annotated. */
	default FeatureSlice meta ( ) {
		return this.getClass().getAnnotation(FeatureSlice.class);
	}

	default void configureCommand ( BoundedContextBuilder<C> builder ) { }

	default void configureQuery ( BoundedContextBuilder<C> builder ) { }

	default void configureAutomation ( BoundedContextBuilder<C> builder ) { }

	default void configureProjection ( BoundedContextBuilder<C> builder ) { }

	default void startCommand ( C boundedContext ) { }

	default void startQuery ( C boundedContext ) { }

	default void startAutomation ( C boundedContext ) { }

	default void startProjection ( C boundedContext ) { }

}
