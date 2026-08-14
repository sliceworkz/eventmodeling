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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A dispatcher's processor now reports its lifecycle the way a read model's projector does —
 * {@code DispatcherStarted}/{@code DispatcherFailed}/{@code DispatcherStopped}. This is the reporting
 * that matters most of the three projector kinds: a dispatcher is the only thing publishing the
 * outbound stream, so its processor being down is deployment-wide silence toward an external system —
 * and it used to be exactly that, two log lines and nothing else. A transiently failing dispatch (the
 * external system being down: the ordinary life of an outbox) is retried with backoff instead of
 * retiring the outbox for the life of the process.
 */
public class DispatcherLifecycleTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "DispatcherLifecycleBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private String previousInitial;
	private String previousMax;

	@BeforeEach
	void shortenRetryPacing ( ) {
		previousInitial = System.setProperty("sliceworkz.eventmodeling.projector.retry.initial.ms", "50");
		previousMax = System.setProperty("sliceworkz.eventmodeling.projector.retry.max.ms", "200");
	}

	@AfterEach
	void restoreRetryPacing ( ) {
		restore("sliceworkz.eventmodeling.projector.retry.initial.ms", previousInitial);
		restore("sliceworkz.eventmodeling.projector.retry.max.ms", previousMax);
	}

	private static void restore ( String property, String previous ) {
		if ( previous == null ) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

	/** Announced at startup, so an idle outbox is not the same silence as a missing one. */
	@Test
	void aDispatcherAnnouncesItselfAtStartup ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.dispatcher(new FlakyDispatcher());
		buildBoundedContext(builder);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertNotNull(started("FlakyDispatcher"), "a dispatcher must announce its processor at startup: " + received));
		assertEquals(CONTEXT_NAME, started("FlakyDispatcher").boundedContext());
	}

	/**
	 * The outbox surviving its external system being down: the dispatch is retried with backoff,
	 * reported per fruitless round, and publishes exactly once when the system comes back — the
	 * outbound event was never skipped, which is the outbox guarantee holding through the failure.
	 */
	@Test
	void aTransientlyFailingDispatchRetriesAndPublishesExactlyOnce ( ) {
		FlakyDispatcher dispatcher = new FlakyDispatcher();
		dispatcher.failing.set(true);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.dispatcher(dispatcher);
		buildBoundedContext(builder);

		appendOutbound(new SomeOutboundEvent("survives-the-outage"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.DispatcherFailed f
								&& f.dispatcher().equals("FlakyDispatcher")),
						"every fruitless retry round must be reported: " + received));

		dispatcher.failing.set(false); // the external system comes back; nothing is restarted

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("survives-the-outage"), dispatcher.published(),
						"the outbound event must be published exactly once, once the system is back"));

		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.DispatcherStopped),
				"a failure worth retrying must not retire the outbox: " + received);
	}

	/** A poison outbound event is permanent — retrying cannot help — and retires the dispatcher, saying so. */
	@Test
	void aPoisonOutboundEventStopsTheDispatcherAndSaysSo ( ) {
		PoisonedDispatcher dispatcher = new PoisonedDispatcher();

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.dispatcher(dispatcher);
		buildBoundedContext(builder);

		appendOutbound(new SomeOutboundEvent("poison"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertNotNull(stopped("PoisonedDispatcher"), "a retired dispatcher must report it: " + received));

		BoundedContextEvent.DispatcherStopped stopped = stopped("PoisonedDispatcher");
		assertEquals(EventDeserializationException.class.getName(), stopped.failure().type());
		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.DispatcherFailed),
				"a permanent failure is not a retrying state: " + received);
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	/**
	 * Appends straight to the outbound stream. The framework's own writers go through an
	 * {@code OutboundCommand}, but the dispatcher's processor subscribes to the stream and cares only
	 * that events arrive on it — and this keeps the test about the dispatcher rather than about
	 * command plumbing.
	 */
	private void appendOutbound ( MockOutboundEvent event ) {
		EventStream<MockOutboundEvent> outboundStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("outbound"), MockOutboundEvent.class);
		outboundStream.append(AppendCriteria.none(), Event.of(event, Tags.none()));
	}

	private BoundedContextEvent.DispatcherStarted started ( String dispatcher ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.DispatcherStarted.class::isInstance)
					.map(BoundedContextEvent.DispatcherStarted.class::cast)
					.filter(e -> dispatcher.equals(e.dispatcher()))
					.findFirst().orElse(null);
		}
	}

	private BoundedContextEvent.DispatcherStopped stopped ( String dispatcher ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.DispatcherStopped.class::isInstance)
					.map(BoundedContextEvent.DispatcherStopped.class::cast)
					.filter(e -> dispatcher.equals(e.dispatcher()))
					.findFirst().orElse(null);
		}
	}

	/** A dispatcher whose external system is down while the flag is set. */
	static class FlakyDispatcher implements Dispatcher<MockOutboundEvent> {

		final AtomicBoolean failing = new AtomicBoolean();
		private final List<String> published = Collections.synchronizedList(new ArrayList<>());

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			if ( failing.get() ) {
				throw new IllegalStateException("the external system this dispatcher publishes to is down");
			}
			published.add(((SomeOutboundEvent) event).someValue());
		}

		List<String> published ( ) {
			return List.copyOf(published);
		}
	}

	/** A dispatcher hitting a poison outbound event — permanent, so its processor retires. */
	static class PoisonedDispatcher implements Dispatcher<MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			throw new EventDeserializationException(EventType.of(event.getClass()), "this outbound event cannot be read");
		}
	}

}
