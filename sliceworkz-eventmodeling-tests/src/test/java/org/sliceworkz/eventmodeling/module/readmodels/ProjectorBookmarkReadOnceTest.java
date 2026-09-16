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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A processor reads its event-store bookmark once, before its first execution, and never again while
 * it runs — whatever the eventstore's {@code Projector} defaults to.
 * <p>
 * The eventstore's bookmark builder defaults to reading the bookmark before <em>each</em> execution,
 * and a projector in that mode follows a bookmark removed or rewound by hand: an absent bookmark
 * resets its position to the start of the stream, so the next run replays everything. The framework
 * sets the read frequency itself on both of {@code ProjectorProcessor}'s branches
 * ({@code readBeforeFirstExecution()}, or {@code readOnManualTriggerOnly()} for a projection keeping
 * its own position), because the processor owns the bookmark it writes: it resumes from it at start
 * and re-seeds on promotion, and nothing else is meant to move it underneath a running processor. Left
 * to the default, an operator removing a bookmark to rebuild a read model — or the framework itself
 * dropping an {@code EPHEMERAL} one — would replay history into a read model that already holds it,
 * with nothing to say so.
 */
public class ProjectorBookmarkReadOnceTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final int PRE_EXISTING_EVENT_COUNT = 5;

	@Test
	void aBookmarkRemovedUnderARunningProcessorDoesNotMakeItReplay ( ) {
		EventStream<MockDomainEvent> domain = domainStream();
		for ( int i = 0; i < PRE_EXISTING_EVENT_COUNT; i++ ) {
			domain.append(AppendCriteria.none(), Event.of(new MockDomainEvent.FirstDomainEvent("event " + i), Tags.none()));
		}

		MockReadModel readModel = new MockReadModel("read once model", ReadModelStorage.LOCAL);
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(readModel).eventuallyConsistent();
		buildBoundedContext(builder);

		waitBecauseOfEventualConsistency(( ) -> readModel.eventCount() == PRE_EXISTING_EVENT_COUNT);

		// the processor's own bookmark, placed after its catch-up
		List<Bookmark> bookmarks = eventStorage().getBookmarks().stream()
				.filter(bookmark -> bookmark.reader().contains(readModel.readmodelName())).toList();
		assertEquals(1, bookmarks.size(), "the processor bookmarks its progress under its own reader name");
		String reader = bookmarks.get(0).reader();

		// removed by hand while the processor runs, as an operator rebuilding a read model would
		eventStorage().removeBookmark(reader);

		domain.append(AppendCriteria.none(), Event.of(new MockDomainEvent.FirstDomainEvent("after the rewind"), Tags.none()));

		waitBecauseOfEventualConsistency(( ) -> readModel.eventCount() >= PRE_EXISTING_EVENT_COUNT + 1);
		// a projector that re-read the absent bookmark before this run would have reset to the start
		// and replayed the whole stream: the count would climb past six, not stop at it
		sleepBriefly();
		assertEquals(PRE_EXISTING_EVENT_COUNT + 1, readModel.eventCount(),
				"the new event is handled once and nothing is replayed: the bookmark is read before the first execution only");

		// and the bookmark is written again, at the new head, from the cursor the processor kept
		assertTrue(eventStorage().getBookmarks().stream().anyMatch(bookmark -> bookmark.reader().equals(reader)),
				"the processor keeps bookmarking its progress after the rewind");
	}

	private EventStream<MockDomainEvent> domainStream ( ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage());
		return eventStore.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("domain"), MockDomainEvent.class);
	}

	private static void sleepBriefly ( ) {
		try {
			Thread.sleep(500);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}
}
