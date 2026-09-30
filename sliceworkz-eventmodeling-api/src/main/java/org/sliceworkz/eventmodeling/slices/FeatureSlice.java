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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a feature slice: one vertical slice of a bounded context, discovered by package
 * scanning from the {@code rootPackage} given to the builder's {@code features()}.
 * <p>
 * The annotated class implements {@link Slice Slice&lt;C&gt;}, and everything that can be read off the
 * code is deliberately not repeated here:
 * <ul>
 * <li><b>The bounded context</b> is the {@code C} of {@code Slice<C>}. It is what decides which context
 *     deploys the slice when several scan one package: a slice is deployed by every context it can be
 *     handed to, so one declared over {@code BoundedContext<?,?,?>} serves them all and a raw
 *     {@code Slice} is kept by every context.</li>
 * <li><b>The name</b> is the class' simple name without its {@code FeatureSlice} suffix
 *     ({@link Slice#name()}).</li>
 * <li><b>The type</b> — state change, state read, automation, translation — is derived from the
 *     components the slice registers in its {@code configure...} methods, by the rule the Sliceworkz
 *     Modeler applies to a modeled slice; see {@link SliceType}.</li>
 * </ul>
 * What is left is what the code cannot say.
 * <p>
 * Nothing here changes how a slice runs. Both values are reported on {@code BoundedContextStarting} and
 * on every kernel event attributed to the slice, which is how a dashboard lays out and filters a
 * deployment; {@code tags} can also select which slices a deployment runs, through the builder's
 * {@code features().filter(...)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FeatureSlice {

	/**
	 * The chapter of the event model the slice belongs to: the column it is grouped under, both in the
	 * model and on a dashboard. Name it as the model does. Empty for a slice in no chapter.
	 */
	String chapter() default "";

	/**
	 * Free-form labels, for filtering a dashboard and for selecting the slices a deployment runs: a
	 * {@code features().filter(slice -> ...)} over {@link Slice#tags()} is how one build is split over
	 * several processes. None by default.
	 */
	String[] tags() default {};

}
