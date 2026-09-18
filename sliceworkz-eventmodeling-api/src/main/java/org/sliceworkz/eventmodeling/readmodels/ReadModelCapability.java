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
 * Reads a live read model of this bounded context: the read model is constructed with the parameters
 * given, projected from the events its {@code eventQuery()} matches, and handed back.
 * <p>
 * <strong>The result is typed by the class it is asked for.</strong> {@code read(AccountDetails.class,
 * id)} is an {@code AccountDetails}, so it is assigned, chained ({@code read(X.class, id).details()}) or
 * held in a {@code var} without a cast, and assigning it to an unrelated type is a compile error. The
 * alternative — a free type parameter on the result, inferred from whatever the caller assigns it to
 * — loses because {@code String s = context.read(AccountDetails.class, id)} then compiles and fails as
 * a {@code ClassCastException} at the call site, on a method whose argument said exactly what would come
 * back. The type parameter is bounded by the read model type this context serves, so the class argument
 * stays constrained to the read models of this context as it always was.
 * <p>
 * The {@code params} are the constructor arguments of the read model, matched by count and left
 * unchecked: which parameters a read model takes is a property of its constructors, and the class
 * argument alone does not say.
 */
public interface ReadModelCapability<DOMAIN_EVENT_TYPE> {

	<READ_MODEL extends ReadModel<? extends DOMAIN_EVENT_TYPE>> READ_MODEL read ( Class<READ_MODEL> readModelClass, Tracing tracing, Object... params );

	<READ_MODEL extends ReadModel<? extends DOMAIN_EVENT_TYPE>> READ_MODEL read ( Class<READ_MODEL> readModelClass, Object... params );

}
