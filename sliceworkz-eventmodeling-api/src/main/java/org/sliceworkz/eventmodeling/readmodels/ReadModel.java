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

import org.sliceworkz.eventstore.events.EventHandler;

/**
 * A read model that handles the event data alone, through {@code when(DOMAIN_EVENT_TYPE)}.
 *
 * <p><b>The one to start with.</b> Registered with {@code builder.readmodel(MyReadModel.class).live()}
 * and read with {@code context.read(MyReadModel.class, ...)}, it is projected when it is read, so it is
 * always current and there is nothing to keep or rebuild. Right whenever the set of events its
 * {@link #eventQuery()} matches is bounded by design — one entity, one period, one savepoint onward.
 *
 * <p>The alternatives, in the order to consider them, are set out in {@code CHOOSING-A-READ-MODEL.md};
 * in short:
 * <ul>
 *   <li>Implement {@link ReadModelWithMetaData} instead where the projection needs an event's tags,
 *       timestamp or reference — this interface is the convenience that hides them.</li>
 *   <li>Extend {@link PublishingReadModel} and register the instance
 *       {@code .eventuallyConsistent()} when the replay cannot be bounded and reads should be free.</li>
 *   <li>Extend {@code SqlReadModelProjector} when that state should be durable or shared.</li>
 *   <li>Implement {@link SeededReadModel} when such a projection exists but a particular read cannot
 *       tolerate its lag.</li>
 * </ul>
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type this read model is projected from
 */
public interface ReadModel<DOMAIN_EVENT_TYPE> extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>, EventHandler<DOMAIN_EVENT_TYPE> {

}
