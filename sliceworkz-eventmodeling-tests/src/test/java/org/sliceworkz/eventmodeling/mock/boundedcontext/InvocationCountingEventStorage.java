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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class InvocationCountingEventStorage implements EventStorage {

	private EventStorage wrapped;
	
	private int queries = 0;
	
	public InvocationCountingEventStorage ( EventStorage wrapped ) {
		this.wrapped = wrapped;
	}
	
	public int queriesDone ( ) {
		return queries;
	}
	
	@Override
	public String name() {
		return wrapped.name();
	}

	/**
	 * Forwards to the wrapped storage. {@code EventStorage.close()} defaults to a no-op, which is right
	 * for a storage holding nothing, but a decorator that inherited that default would silently keep
	 * the storage it wraps open.
	 */
	@Override
	public void close() {
		wrapped.close();
	}

	@Override
	public Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream, EventReference from, Limit limit, QueryDirection queryDirection) {
		queries++;
		return wrapped.query(query, stream, from, limit, queryDirection);
	}

	@Override
	public List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events) {
		return wrapped.append(appendCriteria, stream, events);
	}

	@Override
	public Optional<StoredEvent> getEventById(EventId eventId) {
		return wrapped.getEventById(eventId);
	}

	@Override
	public void subscribe(EventStoreListener listener) {
		wrapped.subscribe(listener);
	}

	@Override
	public Optional<EventReference> getBookmark(String reader) {
		return wrapped.getBookmark(reader);
	}

	@Override
	public void bookmark(String reader, EventReference eventReference, Tags tags ) {
		wrapped.bookmark(reader, eventReference, tags);
	}

	@Override
	public void removeBookmark(String reader) {
		wrapped.removeBookmark(reader);
	}

	@Override
	public List<Bookmark> getBookmarks() {
		return wrapped.getBookmarks();
	}

}
