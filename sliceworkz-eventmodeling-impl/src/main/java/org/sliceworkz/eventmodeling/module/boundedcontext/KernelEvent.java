/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import java.util.Set;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;

public sealed interface KernelEvent {
	
	record BoundedContextStarted ( String boundedContext, String logical, String physical, String process, Set<FeatureSlice> enabledFeatures, Set<FeatureSlice> disabledFeatures ) implements KernelEvent { }

	record LiveModelProjected ( Class<?> readModelClass, Metrics metrics, EventQuery eventQuery ) implements KernelEvent { }
	
	record CommandExecuted ( Class<?> commandClass, Metrics metrics, EventQuery eventQuery ) implements KernelEvent { }
	
	
	/*
	 * Value objects used by Events
	 */
	
	record FeatureSlice ( String name, String type, String context, String chapter, Set<String> tags ) { }
	
	record Metrics ( long durationMs, long queriesDone, long eventStreamed, long eventsHandled, EventReference until ) { }

}
