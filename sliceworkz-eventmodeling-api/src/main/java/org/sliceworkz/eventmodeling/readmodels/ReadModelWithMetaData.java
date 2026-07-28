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

import org.sliceworkz.eventstore.projection.Projection;

public interface ReadModelWithMetaData<DOMAIN_EVENT_TYPE> extends Projection<DOMAIN_EVENT_TYPE> {
	
	/**
	 * By default this returns the classname, but in case the same readmodel class is reused for multiple different instances,
	 * this methods can be overriden to return a different name for each of them.
	 * This allows to differentiate them for different bookmarks for different EventuallyConsistentEventProcessers tasks.
	 */
	default String readmodelName () {
		return this.getClass().getSimpleName();
	}

	/**
	 * Where this read model keeps its projected state, which also decides how it is projected:
	 * ephemeral and local state is projected by every instance of the bounded context, shared state
	 * by a single elected leader.
	 * <p>
	 * Defaults to {@link ReadModelStorage#EPHEMERAL} as most read models are memory-based. Override
	 * for durable read models: {@link ReadModelStorage#SHARED} when all instances read and write the
	 * same storage (e.g. a shared database), {@link ReadModelStorage#LOCAL} when the storage is
	 * durable but private to one instance.
	 */
	default ReadModelStorage storage () {
		return ReadModelStorage.EPHEMERAL;
	}

}
