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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarting;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStopped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStopping;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A context taken out of service with {@code stop()} says so, as one that can be started again on the
 * same process; a terminated one says it went away. Without the first, a stopped context - by code or by
 * an operator's instruction - read as running to every reader of the monitoring stream.
 */
public class BoundedContextStopEventsTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "StopEventsTestBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private Mock build() {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		return buildBoundedContext(builder);
	}

	private List<BoundedContextEvent> lifecycle() {
		return received.stream().filter(e -> e instanceof BoundedContextStarting || e instanceof BoundedContextStarted
				|| e instanceof BoundedContextStopping || e instanceof BoundedContextStopped).toList();
	}

	@Test
	void aStopIsReportedAsOneThatCanBeUndoneAndAStartAfterItIsAnnouncedAgain() {
		Mock domain = build();
		received.clear();

		domain.stop();
		List<BoundedContextEvent> stop = lifecycle();
		assertEquals(2, stop.size(), "expected a Stopping and a Stopped, got: " + stop);
		BoundedContextStopping stopping = (BoundedContextStopping) stop.get(0);
		BoundedContextStopped stopped = (BoundedContextStopped) stop.get(1);
		assertEquals(CONTEXT_NAME, stopping.boundedContext());
		assertTrue(stopping.restartable(), "the process lives on: this stop can be undone");
		assertTrue(stopped.restartable());

		received.clear();
		domain.start();
		List<BoundedContextEvent> start = lifecycle();
		assertTrue(start.get(0) instanceof BoundedContextStarting, "got: " + start);
		assertTrue(start.get(start.size() - 1) instanceof BoundedContextStarted, "got: " + start);
	}

	@Test
	void aTerminationIsReportedAsTheContextGoingAway() {
		Mock domain = build();
		received.clear();

		domain.terminate();
		List<BoundedContextEvent> end = lifecycle();
		assertEquals(2, end.size(), "got: " + end);
		assertFalse(((BoundedContextStopping) end.get(0)).restartable());
		assertFalse(((BoundedContextStopped) end.get(1)).restartable());
	}

	@Test
	void aStopOfAContextThatIsNotRunningReportsNothing() {
		Mock domain = build();
		domain.stop();
		received.clear();

		domain.stop();
		assertTrue(lifecycle().isEmpty(), "a second stop did nothing and must say nothing, got: " + lifecycle());
	}

	@Test
	void aStoppedContextTerminatedAfterwardsReportsItsTermination() {
		Mock domain = build();
		domain.stop();
		received.clear();

		domain.terminate();
		List<BoundedContextEvent> end = lifecycle();
		assertEquals(2, end.size(), "got: " + end);
		assertFalse(((BoundedContextStopping) end.get(0)).restartable());
	}

	@Test
	void aStopEventStoredBeforeThePropertyExistedReadsAsATermination() {
		EventStreamId streamId = EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel");
		eventStorage().append(AppendCriteria.none(), streamId, List.of(
				new EventToStore(streamId, EventType.of(BoundedContextStopping.class),
						"{\"boundedContext\":\"orders\",\"logical\":\"orders\",\"physical\":\"orders-1\",\"process\":\"p1\"}", Tags.none(), null),
				new EventToStore(streamId, EventType.of(BoundedContextStopped.class),
						"{\"boundedContext\":\"orders\",\"logical\":\"orders\",\"physical\":\"orders-1\",\"process\":\"p1\"}", Tags.none(), null)));

		EventStream<BoundedContextEvent> stream = EventStore.on(eventStorage()).build().getEventStream(streamId, BoundedContextEvent.class);
		List<BoundedContextEvent> read = stream.query(EventFilter.matchAll()).stream().map(e -> e.data()).toList();

		assertFalse(((BoundedContextStopping) read.get(0)).restartable());
		assertFalse(((BoundedContextStopped) read.get(1)).restartable());
	}
}
