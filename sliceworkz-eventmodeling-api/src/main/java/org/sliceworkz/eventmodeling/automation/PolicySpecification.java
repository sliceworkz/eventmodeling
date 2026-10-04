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

/**
 * The first of the two choices registering a {@link Policy} requires: where it starts reading the domain
 * stream when it is deployed for the first time. {@code build()} refuses a policy registered without it.
 * <pre>{@code
 * builder.policy(new CancelSubscriptionsOfCancelledSessionPolicy())
 *        .fromNowOn()
 *        .stallOnRejection();
 * }</pre>
 *
 * @param <C> the bounded context type
 */
public interface PolicySpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * Starts at the head of the domain stream, the first time the policy leads: see {@link PolicyStart#FROM_NOW_ON}.
	 *
	 * @return the second choice to make
	 */
	PolicyRejectionSpecification<C> fromNowOn ( );

	/**
	 * Starts at the beginning of the domain stream: see {@link PolicyStart#FROM_THE_BEGINNING}.
	 *
	 * @return the second choice to make
	 */
	PolicyRejectionSpecification<C> fromTheBeginning ( );

}
