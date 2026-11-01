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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.InstanceHeartbeat;
import org.sliceworkz.eventmodeling.boundedcontext.InstanceHeartbeat.Beat;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The heartbeat a {@link StreamAppendingBoundedContextListener} keeps per bounded context: a read position
 * at the monitoring stream's head, refreshed while the process is up - idle or not - and removed when the
 * context terminates, so a row left ageing is a context that went away without saying so.
 */
public class InstanceHeartbeatTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "HeartbeatTestBoundedContext";
	private static final Duration INTERVAL = Duration.ofMillis(200);

	private EventStream<BoundedContextEvent> monitoring;
	private Instance instance;

	private Mock build ( Duration interval ) {
		monitoring = EventStore.on(eventStorage()).build()
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("monitoring"), BoundedContextEvent.class);
		instance = InstanceFactory.determine("unittests");
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(instance)
				.listener(new StreamAppendingBoundedContextListener(monitoring, interval));
		return buildBoundedContext(builder);
	}

	private String reader ( ) {
		return InstanceHeartbeat.reader(CONTEXT_NAME, instance.logical(), instance.physical());
	}

	private Optional<Bookmark> heartbeat ( ) {
		return monitoring.findBookmark(reader());
	}

	@ForEachBackend
	void aStartedContextBeatsAtTheHeadOfTheMonitoringStreamUnderItsOwnName ( ) {
		build(INTERVAL);

		Bookmark bookmark = heartbeat().orElseThrow(() -> new AssertionError("no heartbeat placed for " + reader()));
		Beat beat = InstanceHeartbeat.of(bookmark).orElseThrow();

		assertEquals(CONTEXT_NAME, beat.boundedContext());
		assertEquals(instance.logical(), beat.logical());
		assertEquals(instance.physical(), beat.physical());
		assertEquals(instance.process(), beat.process());
		assertEquals(INTERVAL, beat.interval());
		assertTrue(bookmark.reference().isEmpty(), "a heartbeat handles nothing: it is a read position, never a resume point");
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertEquals(monitoring.head(), heartbeat().orElseThrow().readUpTo(), "a beat reads as fully caught up"));
	}

	@ForEachBackend
	void anIdleContextKeepsRefreshingItsHeartbeat ( ) {
		build(INTERVAL);
		Instant first = heartbeat().orElseThrow().updatedAt();

		// nothing is executed: the context has nothing to report, and beats all the same
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertTrue(heartbeat().orElseThrow().updatedAt().isAfter(first), "the heartbeat was not refreshed"));
	}

	@ForEachBackend
	void terminatingRemovesTheHeartbeatAndNoBeatInFlightPutsItBack ( ) {
		Mock domain = build(INTERVAL);
		assertTrue(heartbeat().isPresent());

		domain.terminate();
		assertTrue(heartbeat().isEmpty(), "a terminated context said so itself; its heartbeat must not linger");
		pause(INTERVAL.multipliedBy(3));
		assertTrue(heartbeat().isEmpty(), "a beat placed after the stop resurrected the heartbeat");
	}

	@Test
	void aStoppedContextKeepsBeatingForItsProcessIsStillThere ( ) {
		// stop() keeps the process up and listening for the instruction to start again; the heartbeat says
		// the process is alive, which it is
		Mock domain = build(INTERVAL);
		domain.stop();
		Instant afterStop = heartbeat().orElseThrow().updatedAt();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertTrue(heartbeat().orElseThrow().updatedAt().isAfter(afterStop)));

		domain.start();
		assertEquals(instance.process(), InstanceHeartbeat.of(heartbeat().orElseThrow()).orElseThrow().process());
	}

	@Test
	void aZeroIntervalPlacesNoHeartbeat ( ) {
		build(Duration.ZERO);
		assertTrue(heartbeat().isEmpty());
		assertFalse(monitoring.getBookmarks().stream().anyMatch(b -> InstanceHeartbeat.isHeartbeat(b.reader())));
	}

	@Test
	void aNegativeIntervalIsRefused ( ) {
		EventStream<BoundedContextEvent> stream = EventStore.on(eventStorage()).build()
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("monitoring"), BoundedContextEvent.class);
		assertThrows(IllegalArgumentException.class, () -> new StreamAppendingBoundedContextListener(stream, Duration.ofSeconds(-1)));
	}

	@Test
	void aBeatIsOverdueOnlyOnceItHasMissedSeveral ( ) {
		Instant at = Instant.parse("2026-11-01T10:00:00Z");
		Beat beat = new Beat("orders", "orders", "orders-1", "p1", Duration.ofSeconds(30), at);

		assertFalse(beat.overdue(at.plusSeconds(60)), "two missed beats are not yet a dead process");
		assertFalse(beat.overdue(at.plusSeconds(90)));
		assertTrue(beat.overdue(at.plusSeconds(91)));
		// a storage clock running ahead of the reader's makes no negative age
		assertEquals(Duration.ZERO, beat.age(at.minusSeconds(10)));
	}

	@Test
	void aBookmarkThatIsNoHeartbeatReadsAsNone ( ) {
		Optional<EventReference> somewhere = Optional.of(EventReference.create(1, 1));
		Bookmark processor = new Bookmark("readmodel:Something", somewhere, Optional.empty(), Tags.none(), Instant.now());
		assertTrue(InstanceHeartbeat.of(processor).isEmpty());

		Bookmark damaged = new Bookmark(InstanceHeartbeat.READER_PREFIX + "x/y/z", Optional.empty(), somewhere, Tags.none(), Instant.now());
		assertTrue(InstanceHeartbeat.of(damaged).isEmpty(), "a heartbeat that does not say which context it is for says nothing");

		Bookmark odd = new Bookmark(InstanceHeartbeat.READER_PREFIX + "x/y/z", Optional.empty(), somewhere,
				Tags.of(InstanceHeartbeat.TAG_BOUNDED_CONTEXT, "x", InstanceHeartbeat.TAG_INTERVAL_MS, "soon"), Instant.now());
		assertEquals(InstanceHeartbeat.DEFAULT_INTERVAL, InstanceHeartbeat.of(odd).orElseThrow().interval());
	}

	private static void pause ( Duration duration ) {
		try {
			Thread.sleep(duration.toMillis());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}
}
