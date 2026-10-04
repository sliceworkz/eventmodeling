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
package org.sliceworkz.eventmodeling.automation;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

/**
 * The second of the two choices registering a {@link Policy} requires: what a business rejection of the
 * command it issues does. {@code build()} refuses a policy registered without it.
 *
 * @param <C> the bounded context type
 */
public interface PolicyRejectionSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * Stalls on a rejected event until the rejection clears or an operator skips it: see
	 * {@link PolicyRejectionHandling#STALL}.
	 *
	 * @return the builder
	 */
	BoundedContextBuilder<C> stallOnRejection ( );

	/**
	 * Records a rejection and moves on: see {@link PolicyRejectionHandling#SKIP}.
	 *
	 * @return the builder
	 */
	BoundedContextBuilder<C> skipRejections ( );

}
