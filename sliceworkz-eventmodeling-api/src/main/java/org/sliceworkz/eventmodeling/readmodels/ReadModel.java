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

/**
 * A read model: a {@link Projection} the bounded context registers, names and projects.
 *
 * <p><b>The one to start with.</b> Registered with {@code builder.readmodel(MyReadModel.class).live()}
 * and read with {@code context.read(MyReadModel.class, ...)}, it is projected when it is read, so it is
 * always current and there is nothing to keep or rebuild. Right whenever the set of events its
 * {@link #eventQuery()} matches is bounded by design — one entity, one period, one savepoint onward.
 *
 * <p>Every event arrives through {@code when(Event<DOMAIN_EVENT_TYPE>)}, with its metadata: the
 * domain event is {@code event.data()}, and the tags, timestamp and reference sit beside it on the
 * same argument. A read model that needs only the domain event switches on {@code event.data()}.
 * There is deliberately no second read model interface taking the domain event alone — see the
 * eventstore's {@link org.sliceworkz.eventstore.events.EventHandler} for why one {@code when} is
 * the whole handler contract.
 *
 * <p>The alternatives, in the order to consider them, are set out in {@code CHOOSING-A-READ-MODEL.md};
 * in short:
 * <ul>
 *   <li>Extend {@link PublishingReadModel} and register the instance
 *       {@code .eventuallyConsistent()} when the replay cannot be bounded and reads should be free.</li>
 *   <li>Extend {@code SqlReadModelProjector} when that state should be durable or shared.</li>
 *   <li>Implement {@link SeededReadModel} when such a projection exists but a particular read cannot
 *       tolerate its lag.</li>
 * </ul>
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type this read model is projected from
 */
public interface ReadModel<DOMAIN_EVENT_TYPE> extends Projection<DOMAIN_EVENT_TYPE> {

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
