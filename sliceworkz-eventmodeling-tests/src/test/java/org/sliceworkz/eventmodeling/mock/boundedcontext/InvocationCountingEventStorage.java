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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
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

	private int heads = 0;

	private final List<EventQuery> queriesSeen = new ArrayList<>();

	private AppendCriteria lastAppendCriteria;

	private Predicate<EventQuery> afterQueryMatching;

	private Runnable afterQueryHook;

	private RuntimeException appendFailure;

	public InvocationCountingEventStorage ( EventStorage wrapped ) {
		this.wrapped = wrapped;
	}

	public int queriesDone ( ) {
		return queries;
	}

	/** How many times the head of a stream was asked for -- a pin, as opposed to a read. */
	public int headsDone ( ) {
		return heads;
	}

	/** Every query issued, in order, so a test can assert on the shape a read was made with. */
	public List<EventQuery> queriesSeen ( ) {
		return List.copyOf(queriesSeen);
	}

	/** The criteria the most recent append was made under, or null when nothing was appended. */
	public AppendCriteria lastAppendCriteria ( ) {
		return lastAppendCriteria;
	}

	/**
	 * Runs {@code hook} once, right after the first query matching {@code which} has been answered by
	 * the wrapped storage. The deterministic way to land an event <em>between</em> two reads of one
	 * command: the storage's query is eager, so by the time the hook runs the read it follows is done
	 * and the read after it has not started.
	 */
	public void afterQuery ( Predicate<EventQuery> which, Runnable hook ) {
		this.afterQueryMatching = which;
		this.afterQueryHook = hook;
	}

	/**
	 * Makes every subsequent append fail, so a caller's handling of a storage that will not take an
	 * event can be pinned down. No real backend can be talked into failing on demand.
	 */
	public void failAppendsWith ( RuntimeException failure ) {
		this.appendFailure = failure;
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
		queriesSeen.add(query);
		Stream<StoredEvent> result = wrapped.query(query, stream, from, limit, queryDirection);
		if ( afterQueryHook != null && afterQueryMatching.test(query) ) {
			Runnable hook = afterQueryHook;
			afterQueryHook = null;
			afterQueryMatching = null;
			hook.run();
		}
		return result;
	}

	/**
	 * Forwarded rather than left to the interface default, which would answer through {@link #query}
	 * and so be counted as a read; a head lookup is a pin, and the two are counted apart here for
	 * the same reason the store meters them apart.
	 */
	@Override
	public Optional<EventReference> head ( Optional<EventStreamId> stream ) {
		heads++;
		return wrapped.head(stream);
	}

	@Override
	public List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events) {
		if ( appendFailure != null ) {
			throw appendFailure;
		}
		lastAppendCriteria = appendCriteria;
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
