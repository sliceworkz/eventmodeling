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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A build that fails part-way has to release what it had already made.
 * <p>
 * It hands the caller nothing — no context, so no {@code terminate()} and no shutdown hook — so
 * anything constructed by then is unreachable, and the {@code EventStore} the builder opened over the
 * caller's storage would stay open with the processors' subscriptions still registered on it. The
 * failure is not exotic: the component registries reject a duplicate or anonymous name from the middle
 * of the build, by which point earlier modules have subscribed their processors to their streams.
 */
public class FailedBuildReleasesWhatItBuiltTest extends AbstractMockDomainTest {

	@Test
	void aBuildThatFailsClosesTheStoreItOpened ( ) {
		SubscriptionRecordingStorage storage = new SubscriptionRecordingStorage(eventStorage());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(storage)
				.instance(InstanceFactory.determine("unittests"));

		// the inbound module is wired first, so this translator's processor has subscribed to the
		// inbound stream by the time the read model registry rejects the duplicate name below
		builder.translator(new ProbeTranslator());

		builder.readmodel(new MockReadModel("same name", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.readmodel(new MockReadModel("same name", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();

		IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(rejected.getMessage().contains("duplicate readmodel name"), rejected.getMessage());

		assertTrue(storage.subscribes > 0, "the translator's processor should have subscribed before the build failed");
		assertEquals(storage.subscribes, storage.unsubscribes,
				"every subscription the failed build made should have been released with the store it opened");
	}

	/**
	 * The failure the caller gets must still be the one that explains the build. Anything that goes
	 * wrong releasing the wreckage is attached to it, never put in its place.
	 */
	@Test
	void theReportedFailureIsStillTheBuildFailure ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(new MockReadModel("same name", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.readmodel(new MockReadModel("same name", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();

		IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(rejected.getMessage().contains("bookmarks would collide"), rejected.getMessage());
	}

	static class ProbeTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// nothing: this translator exists to be registered, not to run
		}
	}

	/**
	 * Counts what the store registers with the storage and what it gives back. Closing an
	 * {@code EventStore} closes the streams that were subscribed to, and each of those unsubscribes
	 * itself here — which is how a test can tell a released store from a leaked one.
	 */
	static class SubscriptionRecordingStorage implements EventStorage {

		private final EventStorage wrapped;

		int subscribes;
		int unsubscribes;

		SubscriptionRecordingStorage ( EventStorage wrapped ) {
			this.wrapped = wrapped;
		}

		@Override public String name ( ) { return wrapped.name(); }
		@Override public void close ( ) { wrapped.close(); }

		@Override
		public Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference from, Limit limit, QueryDirection direction ) {
			return wrapped.query(query, stream, from, limit, direction);
		}

		@Override
		public List<StoredEvent> append ( AppendCriteria criteria, Optional<EventStreamId> stream, List<EventToStore> events ) {
			return wrapped.append(criteria, stream, events);
		}

		@Override public Optional<StoredEvent> getEventById ( EventId eventId ) { return wrapped.getEventById(eventId); }

		@Override
		public void subscribe ( EventStoreListener listener ) {
			subscribes++;
			wrapped.subscribe(listener);
		}

		@Override
		public void unsubscribe ( EventStoreListener listener ) {
			unsubscribes++;
			wrapped.unsubscribe(listener);
		}

		@Override public Optional<EventReference> getBookmark ( String reader ) { return wrapped.getBookmark(reader); }

		@Override
		public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
			wrapped.bookmark(reader, eventReference, tags);
		}

		@Override public void removeBookmark ( String reader ) { wrapped.removeBookmark(reader); }

		@Override public List<Bookmark> getBookmarks ( ) { return new ArrayList<>(wrapped.getBookmarks()); }
	}

}
