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
package org.sliceworkz.eventmodeling.module.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * That an outbound event actually reaches a <em>registered</em> dispatcher's {@code when()}, end to
 * end through {@code OutboundModule}: real registration, real leader-elected processor, real bookmark.
 * <p>
 * This is the delivery path nothing else pins. Every other dispatcher test in the repository asserts
 * registration-time validation, and the published {@code DispatcherTest} base deliberately drives a
 * dispatcher <em>without</em> registering it — so without this test, the wiring from
 * {@code builder.dispatcher(...)} through the projector to {@code when()} could break with every
 * suite still green.
 */
public class DispatcherDeliveryTest extends AbstractMockDomainTest {

	/** Records what was published, thread-safely: {@code when()} runs on the processor's thread. */
	static class RecordingDispatcher implements Dispatcher<MockOutboundEvent> {

		private final List<String> published = Collections.synchronizedList(new ArrayList<>());

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			published.add(((SomeOutboundEvent) event).someValue());
		}

		List<String> published ( ) {
			return List.copyOf(published);
		}
	}

	private final RecordingDispatcher dispatcher = new RecordingDispatcher();

	private Mock startContextWithDispatcher ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.dispatcher(dispatcher);
		return buildBoundedContext(builder);
	}

	@ForEachBackend
	void aKeyedOutboundEventReachesTheRegisteredDispatcher ( ) {
		Mock context = startContextWithDispatcher();

		context.execute(new MockOutboundCommand("first"), "order/1");
		waitBecauseOfEventualConsistency(() -> dispatcher.published().size() >= 1);

		context.execute(new MockOutboundCommand("second"), "order/2");
		waitBecauseOfEventualConsistency(() -> dispatcher.published().size() >= 2);

		assertEquals(List.of("first", "second"), dispatcher.published(), "every outbound event delivered exactly once, in order");
	}

	@Test
	void aDuplicateExecutionUnderTheSameKeyIsNotDeliveredTwice ( ) {
		Mock context = startContextWithDispatcher();

		context.execute(new MockOutboundCommand("first"), "order/1");
		context.execute(new MockOutboundCommand("first"), "order/1"); // the at-least-once retry

		// a later event under a fresh key is the fence: once it has been delivered, everything the
		// duplicate could have produced would already have been delivered too -- no sleep needed
		context.execute(new MockOutboundCommand("fence"), "order/2");
		waitBecauseOfEventualConsistency(() -> dispatcher.published().contains("fence"));

		assertEquals(List.of("first", "fence"), dispatcher.published(), "the duplicate append was deduplicated by its key, so nothing was published twice");
	}

}
