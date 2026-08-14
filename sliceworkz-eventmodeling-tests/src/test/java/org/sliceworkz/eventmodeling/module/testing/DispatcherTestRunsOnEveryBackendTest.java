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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.testing.DispatcherTest;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * That a test written against the <em>published</em> {@link DispatcherTest} runs against every
 * registered event storage, not only the in-memory one — the {@code CommandTestRunsOnEveryBackendTest}
 * guarantee, for the dispatcher base. The storage-sensitive halves are the cursor semantics (a second
 * round delivers only what the backend's storage appended since the first) and the
 * {@code givenExecuted} path, whose outbound append and idempotency-key dedup go through the real
 * command path against the backend.
 */
public class DispatcherTestRunsOnEveryBackendTest extends DispatcherTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	/** The simplest fake external system: it remembers what was published to it. */
	static class RecordingDispatcher implements Dispatcher<MockOutboundEvent> {

		private final List<String> published = new ArrayList<>();

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			published.add(((SomeOutboundEvent) event).someValue());
		}
	}

	private final RecordingDispatcher dispatcher = new RecordingDispatcher();

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@Override
	public Dispatcher<MockOutboundEvent> dispatcher ( ) {
		return dispatcher;
	}

	@ForEachBackend
	void aSecondRoundDeliversOnlyWhatIsNew ( ) {
		given(new SomeOutboundEvent("first"))
			.whenDispatched()
			.delivered(1)
			.and()
			.whenDispatched()
			.nothingDelivered()
			.and()
			.given(new SomeOutboundEvent("second"))
			.whenDispatched()
			.delivered(1);

		assertEquals(List.of("first", "second"), dispatcher.published, "every outbound event published exactly once, in order");
	}

	@ForEachBackend
	void aLostBookmarkRedeliversTheWholeStream ( ) {
		given(new SomeOutboundEvent("first"), new SomeOutboundEvent("second"))
			.whenDispatched()
			.delivered(2)
			.and()
			.whenRedeliveredFromTheStart()
			.delivered(2);

		// this dispatcher does not de-duplicate, so the duplicate publication is visible -- which is
		// exactly what this verb exists to make a test face up to
		assertEquals(List.of("first", "second", "first", "second"), dispatcher.published);
	}

	@ForEachBackend
	void givenExecutedSeedsThroughTheRealCommandPathAndDedupsOnItsKey ( ) {
		var definition = given()
			.givenExecuted(new MockOutboundCommand("first"), "order/1")
			// the at-least-once retry: same command, same item-derived key, appended once
			.givenExecuted(new MockOutboundCommand("first"), "order/1");

		definition.whenDispatched().delivered(1);
		assertEquals(List.of("first"), dispatcher.published);
	}

	@Test
	void aPlainTestStillRunsAgainstTheInMemoryStore ( ) {
		assertNotNull(eventStorage());
		given(new SomeOutboundEvent("in-memory"))
			.whenDispatched()
			.delivered(1);
		assertEquals(List.of("in-memory"), dispatcher.published);
	}

}
