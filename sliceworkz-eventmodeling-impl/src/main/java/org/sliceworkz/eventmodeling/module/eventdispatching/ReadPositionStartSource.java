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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.observability.StreamObservation;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendListener;
import org.sliceworkz.eventstore.stream.BookmarkListener;
import org.sliceworkz.eventstore.stream.EventPage;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.Subscription;

/**
 * The event source a projector reads through when it starts after a <em>read position</em> rather than after
 * an event it handled: a processor deployed to react only to what happens from now on, which has handled
 * nothing yet.
 * <p>
 * The eventstore's projector keeps one cursor, and that cursor is also what it records as the last event
 * handled. Started after the read position, it would therefore write that position as handled on its next
 * idle move — a bookmark naming an event the processor never processed, which is what an operator reads as
 * "the last event it handled". Everything else passes straight through to the stream; a bookmark write
 * naming the start point as handled is turned into a move of the read position alone. Once the processor
 * has handled an event of its own, the reference it writes is that event, and nothing is turned.
 * <p>
 * The other half — resuming after the read position at all, where the eventstore resumes a bookmark that
 * records nothing handled from the beginning — is {@link ProjectorProcessor}'s, which builds the projector
 * {@code startingAfter} it over this source.
 */
final class ReadPositionStartSource<EVENT_TYPE> implements EventSource<EVENT_TYPE> {

	private final EventSource<EVENT_TYPE> stream;
	private final EventReference start;

	ReadPositionStartSource ( EventSource<EVENT_TYPE> stream, EventReference start ) {
		this.stream = stream;
		this.start = start;
	}

	@Override
	public void placeBookmark ( String reader, EventReference reference, EventReference readUpTo, Tags tags ) {
		if ( reference != null && reference.id().equals(start.id()) ) {
			// the start point is where reading began, not an event handled: move the read position only
			stream.placeReadPosition(reader, readUpTo == null ? reference : readUpTo, tags);
		} else {
			stream.placeBookmark(reader, reference, readUpTo, tags);
		}
	}

	@Override
	public void placeReadPosition ( String reader, EventReference readUpTo, Tags tags ) {
		stream.placeReadPosition(reader, readUpTo, tags);
	}

	@Override
	public List<Event<EVENT_TYPE>> query ( EventQuery query, EventReference cursor ) {
		return stream.query(query, cursor);
	}

	@Override
	public EventPage<EVENT_TYPE> page ( EventQuery query, EventReference cursor ) {
		return stream.page(query, cursor);
	}

	@Override
	public Optional<List<Event<EVENT_TYPE>>> getEventById ( EventId eventId ) {
		return stream.getEventById(eventId);
	}

	@Override
	public Optional<EventReference> head ( ) {
		return stream.head();
	}

	@Override
	public Optional<StreamObservation> observation ( ) {
		return stream.observation();
	}

	@Override
	public Subscription subscribe ( AppendListener listener ) {
		return stream.subscribe(listener);
	}

	@Override
	public Subscription subscribe ( BookmarkListener listener ) {
		return stream.subscribe(listener);
	}

	@Override
	public Optional<Bookmark> findBookmark ( String reader ) {
		return stream.findBookmark(reader);
	}

	@Override
	public Optional<EventReference> getBookmark ( String reader ) {
		return stream.getBookmark(reader);
	}

	@Override
	public Optional<EventReference> removeBookmark ( String reader ) {
		return stream.removeBookmark(reader);
	}

	@Override
	public List<Bookmark> getBookmarks ( ) {
		return stream.getBookmarks();
	}

	@Override
	public void close ( ) {
		// the stream is the processor's, shared with its subscription: not ours to close
	}

}
