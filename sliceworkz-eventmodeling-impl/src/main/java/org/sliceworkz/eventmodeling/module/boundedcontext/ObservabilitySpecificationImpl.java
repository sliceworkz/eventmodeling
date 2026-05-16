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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.ObservabilitySpecification;

public class ObservabilitySpecificationImpl<C extends BoundedContext<?,?,?>> implements ObservabilitySpecification<C> {

	private final BoundedContextBuilder<C> boundedContextBuilder;
	private boolean lifecycleEventsEnabled = true;

	public ObservabilitySpecificationImpl ( BoundedContextBuilder<C> boundedContextBuilder ) {
		this.boundedContextBuilder = boundedContextBuilder;
	}

	@Override
	public BoundedContextBuilder<C> enableLifecycleEvents ( ) {
		this.lifecycleEventsEnabled = true;
		return boundedContextBuilder;
	}

	@Override
	public BoundedContextBuilder<C> enableLifecycleEvents ( boolean enableLifecycleEvents ) {
		this.lifecycleEventsEnabled = enableLifecycleEvents;
		return boundedContextBuilder;
	}

	@Override
	public BoundedContextBuilder<C> disableLifecycleEvents ( ) {
		this.lifecycleEventsEnabled = false;
		return boundedContextBuilder;
	}

	public boolean lifecycleEventsEnabled ( ) {
		return lifecycleEventsEnabled;
	}

}
