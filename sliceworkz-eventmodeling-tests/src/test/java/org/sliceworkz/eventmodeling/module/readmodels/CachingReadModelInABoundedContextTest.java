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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CUSTOMER;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.events.TracingScope;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CustomerId;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A {@code CachingReadModel} registered on a bounded context: read by its class, loaded on a miss, kept
 * current by its projector, started at the stream head, and degraded into loading every read while its
 * projector is stopped.
 */
public class CachingReadModelInABoundedContextTest extends AbstractMockDomainTest {

	private static final String CONTEXT = "UnitTestBoundedContext";
	private static final CustomerId C1 = CUSTOMER.id("c1");
	private static final CustomerId C2 = CUSTOMER.id("c2");

	@ForEachBackend
	void aHeldKeyIsKeptCurrentByTheProjector ( ) {
		CustomerValuesReadModel registered = new CustomerValuesReadModel();
		Mock mock = buildBoundedContext(builder().readmodel(registered).eventuallyConsistent());
		append(mock, C1, "a");

		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		assertSame(registered, readModel);
		assertEquals(List.of("a"), readModel.get(C1));

		EventReference b = append(mock, C1, "b");
		append(mock, C2, "not held");
		waitBecauseOfEventualConsistency(() -> readModel.result(C1).includes(b));

		assertEquals(List.of("a", "b"), readModel.get(C1));
		assertEquals(1, readModel.statistics().misses(), "kept current, never loaded again");
		assertFalse(readModel.holds(C2));
	}

	/**
	 * Nothing is held at start, so the history could update nothing: the projector starts at the head
	 * instead of replaying it, which is what keeps a start over ten million customers from reading them all.
	 */
	@ForEachBackend
	void startingDoesNotReplayTheHistory ( ) {
		var stream = eventStore().getEventStream(BoundedContextStreams.domain(CONTEXT), MockDomainEvent.class);
		for ( int i = 0; i < 20; i++ ) {
			stream.append(EphemeralEvent.of(new FirstDomainEvent("v" + i), Tags.of(CUSTOMER.tag(C1))));
		}

		Mock mock = buildBoundedContext(builder().readmodel(new CustomerValuesReadModel()).eventuallyConsistent());

		assertTrue(bookmarkOf(stream.getBookmarks()).map(b -> b.reference().isEmpty()).orElse(true),
				"the projector handled none of the history");
		assertEquals(20, mock.read(CustomerValuesReadModel.class).get(C1).size(), "while a read loads all of it");
	}

	@ForEachBackend
	void aStoppedProjectorDegradesIntoLoadingEveryReadUntilItRunsAgain ( ) {
		Mock mock = buildBoundedContext(builder().readmodel(new CustomerValuesReadModel()).eventuallyConsistent());
		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		append(mock, C1, "a");
		readModel.get(C1);

		assertTrue(mock.stopProcessor(ProcessorKind.READ_MODEL, "CustomerValuesReadModel"));
		assertFalse(readModel.holds(C1), "nothing is kept current, so nothing is held");
		append(mock, C1, "while stopped");
		assertEquals(List.of("a", "while stopped"), readModel.get(C1), "a read loads");
		assertFalse(readModel.holds(C1));

		assertTrue(mock.restartProcessor(ProcessorKind.READ_MODEL, "CustomerValuesReadModel"));
		waitBecauseOfEventualConsistency(() -> !readModel.statistics().halted());
		readModel.get(C1);
		EventReference after = append(mock, C1, "after restart");
		waitBecauseOfEventualConsistency(() -> readModel.result(C1).includes(after));
		assertEquals(List.of("a", "while stopped", "after restart"), readModel.get(C1));
	}

	/**
	 * What a caching read model did reaches the monitoring stream: its size on the start event, and per
	 * window the hits, the loads and what they cost — the unfinished window when the context stops, before
	 * it reports itself stopped. Through a real monitoring stream, because a dashboard reads them back typed.
	 */
	@ForEachBackend
	void itsActivityIsReportedOnTheMonitoringStream ( ) {
		EventStream<BoundedContextEvent> monitoring = eventStore().getEventStream(
				EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		Mock mock = buildBoundedContext(builder()
				.listener(new StreamAppendingBoundedContextListener(monitoring, Duration.ZERO))
				.readmodel(new CustomerValuesReadModel(CachePolicy.maxEntries(10).expireAfterAccess(Duration.ofMinutes(5))))
				.eventuallyConsistent());
		append(mock, C1, "a");
		append(mock, C1, "b");
		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		readModel.get(C1);
		readModel.get(C1);
		readModel.get(C1);

		mock.stop();

		List<BoundedContextEvent> stored = monitoring.query(EventFilter.matchAll()).stream().map(Event::data).toList();
		BoundedContextEvent.ReadModelProjectorStarted started = stored.stream()
				.filter(e -> e instanceof BoundedContextEvent.ReadModelProjectorStarted s && "CustomerValuesReadModel".equals(s.readModel()))
				.map(e -> (BoundedContextEvent.ReadModelProjectorStarted) e).findFirst().orElseThrow();
		assertEquals(new BoundedContextEvent.CacheSettings(10, 300_000L, 60_000L), started.cache());

		List<BoundedContextEvent.CachedReadModelSummarized> summaries = stored.stream()
				.filter(e -> e instanceof BoundedContextEvent.CachedReadModelSummarized)
				.map(e -> (BoundedContextEvent.CachedReadModelSummarized) e).toList();
		assertEquals(2, summaries.stream().mapToLong(BoundedContextEvent.CachedReadModelSummarized::hits).sum());
		assertEquals(1, summaries.stream().mapToLong(BoundedContextEvent.CachedReadModelSummarized::loads).sum());
		assertEquals(2, summaries.stream().mapToLong(BoundedContextEvent.CachedReadModelSummarized::eventsLoaded).sum());
		BoundedContextEvent.CachedReadModelSummarized last = summaries.getLast();
		assertEquals(1, last.size());
		assertEquals(10, last.maxEntries());
		assertEquals("UnitTestBoundedContext", last.boundedContext());
		assertTrue(last.windowEnd().isAfter(last.windowStart()) || last.windowEnd().equals(last.windowStart()));

		int lastSummary = stored.indexOf(last);
		int stoppedAt = stored.indexOf(stored.stream().filter(e -> e instanceof BoundedContextEvent.BoundedContextStopped).findFirst().orElseThrow());
		assertTrue(lastSummary < stoppedAt, "reported before the context reports itself stopped");
	}

	/**
	 * A policy that reports each read puts a {@code CachedReadModelRead} on the monitoring stream per read, in the
	 * flow the reader bound, beside the windows; one that does not puts none there.
	 */
	@ForEachBackend
	void eachReadIsReportedWhenThePolicySaysSo ( ) {
		EventStream<BoundedContextEvent> monitoring = eventStore().getEventStream(
				EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		Mock mock = buildBoundedContext(builder()
				.listener(new StreamAppendingBoundedContextListener(monitoring, Duration.ZERO))
				.readmodel(new CustomerValuesReadModel(CachePolicy.maxEntries(10).reportEachRead()))
				.eventuallyConsistent());
		append(mock, C1, "a");
		append(mock, C1, "b");
		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		try ( TracingScope scope = TracingScope.bind(Tracing.actorAndChannel("alice", "api").correlationId("request-flow")) ) {
			readModel.get(C1);
			readModel.get(C1);
			readModel.getCurrent(C1);
		}

		List<Event<BoundedContextEvent>> stored = monitoring.query(EventFilter.matchAll());
		List<Event<BoundedContextEvent>> reads = stored.stream().filter(e -> e.data() instanceof BoundedContextEvent.CachedReadModelRead).toList();
		assertEquals(List.of("LOADED", "HIT", "HIT"), reads.stream().map(e -> ((BoundedContextEvent.CachedReadModelRead) e.data()).answer()).toList());
		BoundedContextEvent.CachedReadModelRead loaded = (BoundedContextEvent.CachedReadModelRead) reads.getFirst().data();
		assertEquals("CustomerValuesReadModel", loaded.readModel());
		assertEquals(C1.value(), loaded.key(), "an entity id by its value");
		assertEquals(2, loaded.eventsHandled());
		assertTrue(loaded.held());
		assertTrue(reads.stream().allMatch(e -> "request-flow".equals(Tracing.readFrom(e).correlationId())), "in the reader's flow");
		BoundedContextEvent.ReadModelProjectorStarted started = stored.stream().map(Event::data)
				.filter(e -> e instanceof BoundedContextEvent.ReadModelProjectorStarted s && "CustomerValuesReadModel".equals(s.readModel()))
				.map(e -> (BoundedContextEvent.ReadModelProjectorStarted) e).findFirst().orElseThrow();
		assertTrue(started.cache().reportsEachRead());
	}

	@Test
	void noReadIsReportedOnItsOwnUnlessThePolicySaysSo ( ) {
		EventStream<BoundedContextEvent> monitoring = eventStore().getEventStream(
				EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		Mock mock = buildBoundedContext(builder()
				.listener(new StreamAppendingBoundedContextListener(monitoring, Duration.ZERO))
				.readmodel(new CustomerValuesReadModel(CachePolicy.maxEntries(10)))
				.eventuallyConsistent());
		append(mock, C1, "a");
		mock.read(CustomerValuesReadModel.class).get(C1);

		assertTrue(monitoring.query(EventFilter.matchAll()).stream().noneMatch(e -> e.data() instanceof BoundedContextEvent.CachedReadModelRead));
	}

	/** A read model that is no cache says so by saying nothing about a cache. */
	@Test
	void anyOtherReadModelStartsWithoutCacheSettings ( ) {
		EventStream<BoundedContextEvent> monitoring = eventStore().getEventStream(
				EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		buildBoundedContext(builder()
				.listener(new StreamAppendingBoundedContextListener(monitoring, Duration.ZERO))
				.readmodel(new org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel("plain",
						org.sliceworkz.eventmodeling.readmodels.ReadModelStorage.EPHEMERAL))
				.eventuallyConsistent());

		BoundedContextEvent.ReadModelProjectorStarted started = monitoring.query(EventFilter.matchAll()).stream().map(Event::data)
				.filter(e -> e instanceof BoundedContextEvent.ReadModelProjectorStarted).map(e -> (BoundedContextEvent.ReadModelProjectorStarted) e)
				.findFirst().orElseThrow();
		assertEquals(null, started.cache());
	}

	@Test
	void aLiveRegistrationIsRefused ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> builder().readmodel(CustomerValuesReadModel.class).live());
		assertTrue(e.getMessage().contains("eventuallyConsistent()"), e.getMessage());
	}

	private EventReference append ( Mock mock, CustomerId customer, String value ) {
		return mock.event(EphemeralEvent.of(new FirstDomainEvent(value), Tags.of(CUSTOMER.tag(customer)))).orElseThrow();
	}

	private static java.util.Optional<Bookmark> bookmarkOf ( List<Bookmark> bookmarks ) {
		return bookmarks.stream().filter(b -> b.reader().contains("CustomerValuesReadModel")).findFirst();
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

}
