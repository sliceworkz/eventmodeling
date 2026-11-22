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

import java.util.Set;

import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * What a {@link CachingReadModel} keeps its entries by: which keys an event is about, and which events
 * are about a key.
 *
 * <p>The two must agree exactly — {@link #narrow} selects, out of the read model's relevant events, the
 * ones for which {@link #keysOf} answers the key — because one of them decides what a load reads and the
 * other which entries the projector updates. An event a load reads and the projector would not route to
 * the key is counted once; an event the projector routes and a load would not read is missing from every
 * entry loaded after it. {@link #byEntity} agrees by construction, and is the one to use whenever the key
 * is an entity's id.
 *
 * @param <KEY> the key type; it must implement {@code equals} and {@code hashCode} on its value
 */
public interface CacheKeys<KEY> {

	/**
	 * The keys an event is about: none for an event about nothing this read model keeps, several for an
	 * event about several (a transfer between two accounts). Must not return null.
	 */
	Set<KEY> keysOf ( Event<?> event );

	/**
	 * The read model's relevant events narrowed to the ones about {@code key}: what a cache miss reads.
	 *
	 * @param relevant the read model's {@link CachingReadModel#eventFilter() eventFilter()}
	 * @param key      the key being loaded
	 */
	EventFilter narrow ( EventFilter relevant, KEY key );

	/**
	 * Keys an entry by the id of an entity: an event is about every instance of the entity it is tagged
	 * with ({@link Entity#idsIn}), and a key's events are the relevant ones carrying its tag
	 * ({@link Entity#tag(EntityId)}), which on PostgreSQL is a lookup in the tag index.
	 */
	static <ID extends EntityId> CacheKeys<ID> byEntity ( Entity<ID> entity ) {
		if ( entity == null ) {
			throw new IllegalArgumentException("entity must not be null");
		}
		return new CacheKeys<>() {

			@Override
			public Set<ID> keysOf ( Event<?> event ) {
				return entity.idsIn(event.tags());
			}

			@Override
			public EventFilter narrow ( EventFilter relevant, ID key ) {
				return relevant.tagged(entity.tag(key));
			}

			@Override
			public String toString ( ) {
				return "by entity " + entity.name();
			}
		};
	}

}
