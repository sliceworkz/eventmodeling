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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CUSTOMER;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CustomerId;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventmodeling.readmodels.CacheStatistics;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A {@code CachingReadModel} driven by hand: attached to a stream, with this test in the projector's
 * place, calling {@code when} with the events a projector would deliver, when it would deliver them.
 * That is what lets each rule be pinned on its own — above all the one that is otherwise a race: a load
 * whose replay the projector overtakes.
 */
public class CachingReadModelRulesTest extends AbstractMockDomainTest {

	private static final CustomerId C1 = CUSTOMER.id("c1");
	private static final CustomerId C2 = CUSTOMER.id("c2");
	private static final CustomerId C3 = CUSTOMER.id("c3");

	private EventStream<MockDomainEvent> stream;

	@BeforeEach
	void openStream ( ) {
		stream = eventStore().getEventStream(BoundedContextStreams.domain("caching"), MockDomainEvent.class);
	}

	@ForEachBackend
	void aMissReplaysTheKeysHistoryAndAHitIsAnsweredFromMemory ( ) {
		append(C1, "a");
		append(C2, "other");
		append(C1, "b");
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());

		assertEquals(List.of("a", "b"), readModel.get(C1));
		assertEquals(List.of("a", "b"), readModel.get(C1));

		CacheStatistics statistics = readModel.statistics();
		assertEquals(1, statistics.misses());
		assertEquals(1, statistics.hits());
		assertEquals(1, statistics.size(), "only the key read is held");
	}

	@ForEachBackend
	void aDeliveredEventIsFoldedIntoTheKeyItIsAboutWhenThatKeyIsHeld ( ) {
		append(C1, "a");
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);

		readModel.when(append(C1, "b"));
		readModel.when(append(C2, "x"));

		assertEquals(List.of("a", "b"), readModel.get(C1));
		assertFalse(readModel.holds(C2), "an event about a key not held is dropped");
		assertEquals(1, readModel.statistics().misses());
	}

	/**
	 * A current read is as current as a live one: what is stored when it starts and the projector has not
	 * delivered yet is read into the held entry, once — the projector delivering it afterwards adds nothing.
	 */
	@ForEachBackend
	void aCurrentReadCatchesTheHeldKeyUpWithWhatTheProjectorHasNotDeliveredYet ( ) {
		append(C1, "a");
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);
		Event<MockDomainEvent> b = append(C1, "b");
		Event<MockDomainEvent> x = append(C2, "x");

		assertEquals(List.of("a"), readModel.get(C1), "a plain read is as current as the projector");
		assertEquals(List.of("a", "b"), readModel.getCurrent(C1));
		assertEquals(1, readModel.statistics().eventsCaughtUp());
		assertEquals(List.of("a", "b"), readModel.get(C1), "the catch-up is kept");

		readModel.when(b);
		readModel.when(x);
		assertEquals(List.of("a", "b"), readModel.getCurrent(C1), "the projector delivering it again counts for nothing");
		assertEquals(b.reference(), readModel.current(C1).upTo());
		assertEquals(1, readModel.statistics().misses());
		assertFalse(readModel.holds(C2));
	}

	@ForEachBackend
	void aCurrentReadOfAKeyNotHeldLoadsIt ( ) {
		append(C1, "a");
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		append(C1, "b");

		assertEquals(List.of("a", "b"), readModel.getCurrent(C1));
		assertTrue(readModel.holds(C1));
		assertEquals(1, readModel.statistics().misses());
		assertEquals(0, readModel.statistics().eventsCaughtUp());
	}

	/**
	 * The projector delivers the events the load already read when it was behind the load's head — and
	 * again after a failed batch. Neither is counted twice.
	 */
	@ForEachBackend
	void anEventTheLoadReadIsNotFoldedAgainWhenTheProjectorDeliversIt ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		Event<MockDomainEvent> a = append(C1, "a");
		Event<MockDomainEvent> b = append(C1, "b");

		readModel.get(C1);
		readModel.when(a);
		readModel.when(b);
		readModel.when(b);

		assertEquals(List.of("a", "b"), readModel.get(C1));
	}

	/**
	 * The race the design hinges on: the projector got past the head the load replayed up to while the key
	 * was not held, so the events in between were delivered to nobody. The load reads them before it
	 * inserts. A stream answering a head older than the projector's position puts the load in exactly that
	 * spot, deterministically.
	 */
	@ForEachBackend
	void aLoadTheProjectorOvertookReadsWhatTheProjectorDeliveredMeanwhile ( ) {
		Event<MockDomainEvent> a = append(C1, "a");
		AtomicReference<EventReference> staleHead = new AtomicReference<>();
		CustomerValuesReadModel readModel = new CustomerValuesReadModel();
		readModel.attach(withHead(stream, staleHead));
		readModel.resumeFrom();

		Event<MockDomainEvent> b = append(C1, "b");
		Event<MockDomainEvent> c = append(C1, "c");
		readModel.when(b);
		readModel.when(c);
		append(C1, "d"); // not delivered yet: it will be, and is not to be read by the load

		staleHead.set(a.reference());
		assertEquals(List.of("a", "b", "c"), readModel.get(C1), "the replay read a, the catch-up b and c");

		readModel.when(append(C1, "e"));
		assertEquals(List.of("a", "b", "c", "e"), readModel.get(C1));
	}

	/**
	 * Two reads of a key nobody holds make one load: the second waits for the first's and answers what it
	 * loaded. A stream whose {@code head()} blocks until the test releases it holds the first load in
	 * flight while the second read arrives.
	 */
	@ForEachBackend
	void aReadOfAKeyBeingLoadedWaitsForThatLoadInsteadOfMakingItsOwn ( ) throws Exception {
		append(C1, "a");
		Gate gate = new Gate();
		CustomerValuesReadModel readModel = gated(gate);

		gate.close();
		CompletableFuture<List<String>> first = CompletableFuture.supplyAsync(() -> readModel.get(C1));
		gate.awaitBlocked();
		CompletableFuture<List<String>> second = CompletableFuture.supplyAsync(() -> readModel.get(C1));
		waitBecauseOfEventualConsistency(() -> readModel.statistics().sharedLoads() == 1);
		gate.open();

		assertEquals(List.of("a"), first.get(10, TimeUnit.SECONDS));
		assertEquals(List.of("a"), second.get(10, TimeUnit.SECONDS));
		assertEquals(1, readModel.statistics().misses(), "one load");
		assertEquals(1, gate.heads(), "and one replay of the key's history");
	}

	/** What the load threw reaches every read that waited for it, and the next read loads afresh. */
	@Test
	void aFailedLoadFailsTheReadsThatWaitedForItAndIsNotKept ( ) throws Exception {
		Gate gate = new Gate();
		CustomerValuesReadModel readModel = gated(gate);
		gate.failWith(new IllegalStateException("store unreachable"));

		gate.close();
		CompletableFuture<List<String>> first = CompletableFuture.supplyAsync(() -> readModel.get(C1));
		gate.awaitBlocked();
		CompletableFuture<List<String>> second = CompletableFuture.supplyAsync(() -> readModel.get(C1));
		waitBecauseOfEventualConsistency(() -> readModel.statistics().sharedLoads() == 1);
		gate.open();

		assertEquals("store unreachable", rootMessage(assertThrows(Exception.class, () -> first.get(10, TimeUnit.SECONDS))));
		assertEquals("store unreachable", rootMessage(assertThrows(Exception.class, () -> second.get(10, TimeUnit.SECONDS))));

		gate.failWith(null);
		assertEquals(List.of(), readModel.get(C1), "nothing of the failed load is left to wait for");
	}

	/**
	 * A load already under way may have read the stream before the caller's write, so a read that must
	 * include the write does not settle for it: it waits for that load, finds the write missing, and loads
	 * the key itself.
	 */
	@ForEachBackend
	void aReadIncludingAWriteDoesNotSettleForALoadThatStartedBeforeIt ( ) throws Exception {
		append(C1, "a");
		Gate gate = new Gate();
		CustomerValuesReadModel readModel = gated(gate);

		gate.close();
		CompletableFuture<List<String>> before = CompletableFuture.supplyAsync(() -> readModel.get(C1));
		gate.awaitBlocked();
		Event<MockDomainEvent> write = append(C1, "mine");
		CompletableFuture<List<String>> including = CompletableFuture.supplyAsync(() -> readModel.getIncluding(C1, write.reference()));
		waitBecauseOfEventualConsistency(() -> readModel.statistics().sharedLoads() == 1);
		gate.open();

		before.get(10, TimeUnit.SECONDS);
		assertEquals(List.of("a", "mine"), including.get(10, TimeUnit.SECONDS));
	}

	/** Every read is observed, on the caller's thread, as a hit, a load or a load shared with another read. */
	@Test
	void everyReadIsObservedWithWhatItAnswered ( ) {
		append(C1, "a");
		RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();
		CustomerValuesReadModel readModel = new CustomerValuesReadModel();
		readModel.attach(stream, observer, "caching");
		readModel.resumeFrom();

		readModel.get(C1);
		readModel.get(C1);

		List<RecordingBoundedContextObserver.Recording> reads = observer.recordings(Observation.CachedRead.class);
		assertEquals(2, reads.size());
		Observation.CachedRead read = reads.get(0).observation(Observation.CachedRead.class);
		assertEquals("caching", read.boundedContext());
		assertEquals("CustomerValuesReadModel", read.readModel());
		assertEquals(C1.value(), read.key());
		Outcome.CacheLoaded loaded = reads.get(0).outcome(Outcome.CacheLoaded.class);
		assertEquals(1, loaded.eventsHandled());
		assertTrue(loaded.held());
		assertTrue(reads.get(1).outcome().orElseThrow() instanceof Outcome.CacheHit);
		assertEquals(List.of(), observer.violations());
	}

	/** The catch-up of an overtaken load is reported: how many events the projector had delivered past the replay. */
	@ForEachBackend
	void anOvertakenLoadReportsItsCatchUp ( ) {
		Event<MockDomainEvent> a = append(C1, "a");
		AtomicReference<EventReference> staleHead = new AtomicReference<>();
		RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();
		CustomerValuesReadModel readModel = new CustomerValuesReadModel();
		readModel.attach(withHead(stream, staleHead), observer, "caching");
		readModel.resumeFrom();
		readModel.when(append(C1, "b"));
		staleHead.set(a.reference());

		readModel.get(C1);

		Outcome.CacheLoaded loaded = observer.last(Observation.CachedRead.class).outcome(Outcome.CacheLoaded.class);
		assertEquals(2, loaded.eventsHandled());
		assertEquals(1, loaded.caughtUp());
	}

	@ForEachBackend
	void anEventAboutTwoKeysIsFoldedIntoBoth ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);
		readModel.get(C2);

		readModel.when(append(Tags.of(CUSTOMER.tag(C1), CUSTOMER.tag(C2)), "shared"));

		assertEquals(List.of("shared"), readModel.get(C1));
		assertEquals(List.of("shared"), readModel.get(C2));
		assertEquals(List.of("shared"), attached(new CustomerValuesReadModel()).get(C2), "and a load of either reads it too");
	}

	@ForEachBackend
	void anIrrelevantEventIsNeitherLoadedNorFolded ( ) {
		stream.append(Event.of(new ThirdDomainEvent("irrelevant"), Tags.of(CUSTOMER.tag(C1))));
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());

		assertEquals(List.of(), readModel.get(C1));
	}

	@Test
	void theKeyReadLeastRecentlyIsEvictedWhenALoadNeedsTheRoom ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel(CachePolicy.maxEntries(2)));
		readModel.get(C1);
		readModel.get(C2);
		readModel.get(C1);
		readModel.get(C3);

		assertTrue(readModel.holds(C1));
		assertFalse(readModel.holds(C2), "read least recently");
		assertTrue(readModel.holds(C3));
		assertEquals(1, readModel.statistics().evictions());

		readModel.when(append(C2, "while evicted"));
		assertEquals(List.of("while evicted"), readModel.get(C2), "an evicted key loads again, missing nothing");
	}

	/** A projector update is not a read: it does not save a key from eviction. */
	@Test
	void anUpdateDoesNotCountAsARead ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel(CachePolicy.maxEntries(2)));
		readModel.get(C1);
		readModel.get(C2);
		readModel.when(append(C1, "update"));
		readModel.get(C3);

		assertFalse(readModel.holds(C1));
		assertTrue(readModel.holds(C2));
	}

	@Test
	void aKeyNotReadForTheIdleTimeExpires ( ) {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
		InstantSource clock = now::get;
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel(
				CachePolicy.maxEntries(10).expireAfterAccess(Duration.ofMinutes(5)).clock(clock)));
		readModel.get(C1);
		readModel.get(C2);

		now.set(now.get().plus(Duration.ofMinutes(3)));
		readModel.get(C2);
		now.set(now.get().plus(Duration.ofMinutes(3)));
		readModel.get(C3);

		assertFalse(readModel.holds(C1), "not read for six minutes");
		assertTrue(readModel.holds(C2), "read three minutes ago");
		assertEquals(1, readModel.statistics().expirations());
	}

	/**
	 * A fold that throws in the projector would hold up every other key while the projector retried, so
	 * the key is let go instead; its next read replays the same event and fails there, to the caller.
	 */
	@Test
	void aFoldThatThrowsLetsTheKeyGoAndItsNextReadFails ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);
		readModel.get(C2);

		readModel.when(append(C1, "boom"));
		readModel.when(append(C2, "fine"));

		assertFalse(readModel.holds(C1));
		assertEquals(1, readModel.statistics().failedUpdates());
		assertEquals(List.of("fine"), readModel.get(C2), "the other keys carry on");
		assertThrows(ProjectorException.class, () -> readModel.get(C1));
	}

	@Test
	void whileTheProjectionFailsNothingIsHeldAndEveryReadLoads ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);

		readModel.projectionFailing();
		assertFalse(readModel.holds(C1));
		append(C1, "while failing");
		assertEquals(List.of("while failing"), readModel.get(C1), "loaded, so current");
		assertFalse(readModel.holds(C1), "but not held: nothing would keep it current");
		assertTrue(readModel.statistics().halted());

		readModel.projectionRunning();
		readModel.get(C1);
		assertTrue(readModel.holds(C1), "a completed run ends the failure");
	}

	/**
	 * A run under way when the projector is stopped completes after the stop: that is no reason to hold
	 * entries again. Only starting the projector is.
	 */
	@Test
	void aStoppedProjectionHoldsNothingUntilItIsStartedAgain ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);

		readModel.projectionStopped();
		readModel.projectionRunning();
		readModel.get(C1);
		assertFalse(readModel.holds(C1));

		readModel.projectionStarted();
		readModel.get(C1);
		assertTrue(readModel.holds(C1));
	}

	@Test
	void aProjectorRebuiltFromTheHeadLetsEverythingGo ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);
		append(C1, "a");

		Optional<EventReference> head = readModel.resumeFrom();

		assertFalse(readModel.holds(C1));
		assertEquals(stream.head(), head, "it projects from the head on");
	}

	@ForEachBackend
	void aReadIncludingAWriteLoadsAHeldKeyThatDoesNotIncludeItYet ( ) {
		CustomerValuesReadModel readModel = attached(new CustomerValuesReadModel());
		readModel.get(C1);
		Event<MockDomainEvent> write = append(C1, "mine");

		assertFalse(readModel.result(C1).includes(write.reference()), "the projector has not delivered it");
		assertEquals(List.of("mine"), readModel.getIncluding(C1, write.reference()));
		assertTrue(readModel.result(C1).includes(write.reference()));
	}

	@Test
	void aReadBeforeTheReadModelIsAttachedIsRefused ( ) {
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CustomerValuesReadModel().get(C1));
		assertTrue(e.getMessage().contains("not attached"), e.getMessage());
	}

	@Test
	void aPolicyMustHoldSomething ( ) {
		assertThrows(IllegalArgumentException.class, () -> CachePolicy.maxEntries(0));
		assertThrows(IllegalArgumentException.class, () -> CachePolicy.maxEntries(1).expireAfterAccess(Duration.ZERO));
	}

	private CustomerValuesReadModel attached ( CustomerValuesReadModel readModel ) {
		readModel.attach(stream);
		readModel.resumeFrom();
		return readModel;
	}

	private Event<MockDomainEvent> append ( CustomerId customer, String value ) {
		return append(Tags.of(CUSTOMER.tag(customer)), value);
	}

	private Event<MockDomainEvent> append ( Tags tags, String value ) {
		return stream.append(Event.of(new FirstDomainEvent(value), tags)).get(0);
	}

	/** The stream, answering {@code head()} with the reference held, when one is. */
	@SuppressWarnings("unchecked")
	private static EventSource<MockDomainEvent> withHead ( EventSource<MockDomainEvent> source, AtomicReference<EventReference> head ) {
		return (EventSource<MockDomainEvent>) Proxy.newProxyInstance(EventSource.class.getClassLoader(), new Class<?>[] { EventSource.class },
				( proxy, method, args ) -> {
					if ( method.getName().equals("head") && method.getParameterCount() == 0 && head.get() != null ) {
						return Optional.of(head.get());
					}
					try {
						return method.invoke(source, args);
					} catch ( InvocationTargetException e ) {
						throw e.getCause();
					}
				});
	}

	private CustomerValuesReadModel gated ( Gate gate ) {
		CustomerValuesReadModel readModel = new CustomerValuesReadModel();
		readModel.attach(gate.in(stream));
		readModel.resumeFrom();
		return readModel;
	}

	private static String rootMessage ( Throwable t ) {
		Throwable root = t;
		while ( root.getCause() != null ) {
			root = root.getCause();
		}
		return root.getMessage();
	}

	/**
	 * A stream whose {@code head()}, while the gate is closed, blocks until it is opened — the first thing
	 * a load does — and optionally throws once released.
	 */
	private static final class Gate {

		private volatile CountDownLatch open = new CountDownLatch(0);
		private final CountDownLatch blocked = new CountDownLatch(1);
		private final AtomicInteger heads = new AtomicInteger();
		private volatile RuntimeException failure;

		void close ( ) {
			open = new CountDownLatch(1);
		}

		void open ( ) {
			open.countDown();
		}

		void failWith ( RuntimeException failure ) {
			this.failure = failure;
		}

		void awaitBlocked ( ) throws InterruptedException {
			assertTrue(blocked.await(10, TimeUnit.SECONDS), "a load reached the stream");
		}

		int heads ( ) {
			return heads.get();
		}

		@SuppressWarnings("unchecked")
		EventSource<MockDomainEvent> in ( EventSource<MockDomainEvent> source ) {
			return (EventSource<MockDomainEvent>) Proxy.newProxyInstance(EventSource.class.getClassLoader(), new Class<?>[] { EventSource.class },
					( proxy, method, args ) -> {
						if ( method.getName().equals("head") && method.getParameterCount() == 0 ) {
							CountDownLatch current = open;
							if ( current.getCount() > 0 ) {
								heads.incrementAndGet();
								blocked.countDown();
								current.await(10, TimeUnit.SECONDS);
								if ( failure != null ) {
									throw failure;
								}
							}
						}
						try {
							return method.invoke(source, args);
						} catch ( InvocationTargetException e ) {
							throw e.getCause();
						}
					});
		}
	}

}
