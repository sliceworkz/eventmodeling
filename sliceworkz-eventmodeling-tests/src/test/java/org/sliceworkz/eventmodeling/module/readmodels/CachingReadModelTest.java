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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CustomerId;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventmodeling.readmodels.CacheStatistics;
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
public class CachingReadModelTest extends AbstractMockDomainTest {

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

}
