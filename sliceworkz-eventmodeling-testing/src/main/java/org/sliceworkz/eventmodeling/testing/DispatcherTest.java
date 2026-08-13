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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.stream.AppendCriteria;

/**
 * Base for testing {@link Dispatcher}s: seed the outbound stream, drive the dispatcher over it, and
 * assert on the fake external system the dispatcher publishes to — synchronously, on the test thread.
 * <pre>{@code
 * class AnnouncePaymentDispatcherTest extends DispatcherTest<PaymentsDomainEvent, Void, PaymentsOutboundEvent> {
 *
 *     private final RecordingMessageBus bus = new RecordingMessageBus();
 *
 *     @Override
 *     public Dispatcher<PaymentsOutboundEvent> dispatcher ( ) {
 *         return new AnnouncePaymentDispatcher(bus);
 *     }
 *
 *     @Test
 *     void aPaymentAnnouncementReachesTheBus ( ) {
 *         given(new PaymentAnnounced("p1"))
 *             .whenDispatched()
 *             .delivered(1);
 *         assertEquals(List.of("p1"), bus.messages());
 *     }
 * }
 * }</pre>
 * A dispatcher is a projection over the outbound stream, and that is exactly how the harness drives
 * it: a projector reads the seeded events and hands each to {@code when()}. The test keeps its own
 * reference to the fake external system and asserts on it — the harness only says how many outbound
 * events were delivered.
 *
 * <h2>Seeding the outbound stream</h2>
 * {@link #given} appends raw outbound events — the outbound stream's idempotency-key requirement
 * lives in the command path, not in storage, so a raw fixture seed is legitimate and keeps the base
 * usable without owning a command. {@link TestDefinition#givenExecuted} is the faithful alternative:
 * it executes a real {@link OutboundCommand} through the bounded context under an externally provided
 * idempotency key, exactly as an automation's {@code publishAndRecord} does.
 *
 * <h2>Redelivery is first-class, because a dispatcher is where duplicates cost most</h2>
 * A dispatcher's bookmark records what has already been published to an external system, and an
 * absent bookmark means "publish everything again" — the worst outcome the framework documents. Two
 * verbs make both halves testable:
 * <ul>
 * <li>{@link TestDefinition#whenDispatched()} keeps one projector across rounds — its cursor plays
 * the bookmark, so a second round delivers only what is new.</li>
 * <li>{@link TestDefinition#whenRedeliveredFromTheStart()} builds a fresh projector from zero — the
 * lost-bookmark (or renamed-dispatcher) case, delivering the whole stream again. Follow it with
 * assertions on the fake external system to prove the dispatcher either de-duplicates on its own
 * keys or that a duplicate publication is acceptable.</li>
 * </ul>
 *
 * <h2>What not to do, and what this deliberately does not cover</h2>
 * <b>Never register the dispatcher on the builder</b> (via {@link #configure}): a registered
 * dispatcher runs on a real leader-elected processor whose thread would race these synchronous
 * rounds. The processor around the projection — leader election, bookmarks in the event store, the
 * naming rules — is framework behaviour, pinned by the framework's own integration tests
 * ({@code DispatcherDeliveryTest} covers the end-to-end delivery path).
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class DispatcherTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	private Dispatcher<OUTBOUND_EVENT_TYPE> dispatcherUnderTest;
	private Projector<OUTBOUND_EVENT_TYPE> dispatcherProjector;

	/**
	 * The dispatcher under test. Called once per test method; return a fresh instance, and keep your
	 * own reference to the fake external system it publishes to — that is what the test asserts on.
	 */
	public abstract Dispatcher<OUTBOUND_EVENT_TYPE> dispatcher ( );

	/**
	 * Nothing to register: the harness owns the dispatcher and drives it itself (see the class
	 * javadoc for why registering it would break the tests). Override only to register something else
	 * the scenario needs.
	 */
	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") OUTBOUND_EVENT_TYPE... outboundEvents ) {
		return new TestDefinition().given(outboundEvents);
	}

	/**
	 * Called lazily from the first fluent call rather than from {@code setUp()}, so a subclass'
	 * {@code dispatcher()} may build on fields its own {@code @BeforeEach} assigns.
	 */
	private Dispatcher<OUTBOUND_EVENT_TYPE> dispatcherUnderTest ( ) {
		if ( dispatcherUnderTest == null ) {
			dispatcherUnderTest = dispatcher();
			dispatcherProjector = Projector.from(outboundStream()).towards(dispatcherUnderTest).build();
		}
		return dispatcherUnderTest;
	}

	public class TestDefinition {

		/** Appends outbound events directly to the outbound stream, as fixture data. */
		public TestDefinition given ( @SuppressWarnings("unchecked") OUTBOUND_EVENT_TYPE... outboundEvents ) {
			Arrays.asList(outboundEvents).forEach(e -> outboundStream().append(AppendCriteria.none(), Event.of(e, Tags.none())));
			return this;
		}

		public TestDefinition events ( @SuppressWarnings("unchecked") OUTBOUND_EVENT_TYPE... outboundEvents ) {
			return given(outboundEvents);
		}

		public TestDefinition event ( OUTBOUND_EVENT_TYPE outboundEvent, Tags tags ) {
			outboundStream().append(AppendCriteria.none(), Event.of(outboundEvent, tags));
			return this;
		}

		/**
		 * Seeds the outbound stream through the production write path instead: a real
		 * {@link OutboundCommand}, executed by the bounded context under {@code idempotencyKey} — so
		 * the command's own guards (the mandatory idempotency key above all) apply exactly as they
		 * would under an automation's {@code publishAndRecord}. Executing it twice under the same key
		 * appends once, which makes the at-least-once retry seedable too.
		 */
		public TestDefinition givenExecuted ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, String idempotencyKey ) {
			kernel().execute(command, idempotencyKey);
			return this;
		}

		/**
		 * Delivers everything the dispatcher has not seen yet. One projector is kept across rounds,
		 * its cursor playing the production bookmark: a second {@code whenDispatched()} delivers only
		 * what was seeded since the first.
		 */
		public DispatchResult whenDispatched ( ) {
			dispatcherUnderTest();
			return new DispatchResult(this, dispatcherProjector.run().eventsHandled());
		}

		/**
		 * Delivers the whole outbound stream again, through a fresh projector with no memory — the
		 * lost-bookmark case (or a renamed dispatcher, which is the same thing: the bookmark is keyed
		 * on the name). Assert on the fake external system afterwards to prove the dispatcher
		 * tolerates it.
		 */
		public DispatchResult whenRedeliveredFromTheStart ( ) {
			return new DispatchResult(this, Projector.from(outboundStream()).towards(dispatcherUnderTest()).build().run().eventsHandled());
		}
	}

	/** What one dispatch round delivered. Chainable; {@link #and()} continues with the next round. */
	public class DispatchResult {

		private final TestDefinition definition;
		private final long eventsHandled;

		private DispatchResult ( TestDefinition definition, long eventsHandled ) {
			this.definition = definition;
			this.eventsHandled = eventsHandled;
		}

		/** Asserts how many outbound events reached the dispatcher's {@code when()} in this round. */
		public DispatchResult delivered ( long expectedEventsHandled ) {
			assertEquals(expectedEventsHandled, eventsHandled, "number of outbound events delivered to the dispatcher not as expected");
			return this;
		}

		/** Asserts this round delivered nothing — the dispatcher was already caught up. */
		public DispatchResult nothingDelivered ( ) {
			return delivered(0);
		}

		/** Continues with the next round: more seeded events, another dispatch. */
		public TestDefinition and ( ) {
			return definition;
		}
	}

}
