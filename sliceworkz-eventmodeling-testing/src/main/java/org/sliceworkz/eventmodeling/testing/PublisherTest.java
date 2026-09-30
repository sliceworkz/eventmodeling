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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.outbound.Publication;
import org.sliceworkz.eventmodeling.module.readmodels.LiveModelConstructors;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Base for testing {@link Publisher}s: seed the domain stream, publish, and assert on the outbound events
 * that were appended — synchronously, on the test thread.
 * <pre>{@code
 * class PlannedSessionPublisherTest extends PublisherTest<PlanningDomainEvent, PlanningInboundEvent, PlanningOutboundEvent> {
 *
 *     @Override
 *     public Publisher<PlanningDomainEvent, PlanningOutboundEvent> publisher ( ) {
 *         return new PlannedSessionPublisher();
 *     }
 *
 *     @Test
 *     void aPlannedSessionIsPublishedAsItWasPlanned ( ) {
 *         given(new SessionPlanned(...), Planning.SESSION.tags(sessionId))
 *             .and(new SessionCapacityChanged(...), Planning.SESSION.tags(sessionId))
 *         .whenPublished()
 *             .published(new PlannedSessionPublished(... capacity 20 ...),
 *                        new PlannedSessionPublished(... capacity 25 ...));
 *     }
 * }
 * }</pre>
 * Each round publishes the domain events the publisher has not been handed yet, through the same
 * {@link Publication} a deployed publisher's processor runs — the keys, the {@code x-published-from} tag,
 * the pinned head and the first-publication-stands rule are the production ones. Live read models are read
 * as the publisher asks for them, bounded exactly as a deployment bounds them; they need no registration here.
 *
 * <h2>Redelivery</h2>
 * {@link TestDefinition#whenRepublished()} hands every domain event over again, as a crash between the
 * append and the bookmark or a failover overlap does. What was published the first time stands, so a
 * publisher is correct when that round appends nothing — {@code .whenRepublished().nothingPublished()} —
 * whether it maps an event the same way again (a retry the store swallows) or, reading the latest state,
 * another way (recognised as published before).
 *
 * <h2>What this deliberately does not cover</h2>
 * <b>Never register the publisher on the builder</b> (via {@link #configure}): the harness drives it itself.
 * The processor around it — leader election, the bookmark, the build-time checks on its aspect and the read
 * models it declares — is framework behaviour, pinned by the framework's own tests.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class PublisherTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	private Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisherUnderTest;
	private EventReference handedOverUpTo;

	/**
	 * The publisher under test. Called once per test method.
	 */
	public abstract Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher ( );

	/**
	 * Nothing to register: the harness drives the publisher and reads its live read models itself.
	 * Override only to register something else the scenario needs.
	 */
	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
		return new TestDefinition().given(events);
	}

	public TestDefinition given ( DOMAIN_EVENT_TYPE event, Tags tags ) {
		return new TestDefinition().and(event, tags);
	}

	/**
	 * Called lazily, so a subclass' {@code publisher()} may build on fields its own {@code @BeforeEach} assigns.
	 */
	private Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisherUnderTest ( ) {
		if ( publisherUnderTest == null ) {
			publisherUnderTest = publisher();
		}
		return publisherUnderTest;
	}

	/** Reads a live read model up to an event, as a deployment's bounded read does. */
	private final Publication.BoundedReader<DOMAIN_EVENT_TYPE> reader = new Publication.BoundedReader<>() {
		@Override
		@SuppressWarnings("unchecked")
		public <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R read ( Class<R> readModelClass, EventReference until, Tracing tracing, Object... params ) {
			R readModel;
			try {
				readModel = (R) LiveModelConstructors.select(readModelClass, params).newInstance(params);
			} catch ( InvocationTargetException e ) {
				throw new IllegalStateException("the constructor of live read model %s threw".formatted(readModelClass.getName()), e.getCause());
			} catch ( ReflectiveOperationException e ) {
				throw new IllegalStateException("live read model %s could not be instantiated".formatted(readModelClass.getName()), e);
			}
			Projector.from(domainStream()).into((ReadModel<DOMAIN_EVENT_TYPE>) readModel).build().runUntil(until);
			return readModel;
		}
	};

	/** One round: every matching domain event after {@code after} handed to the publisher, in order. */
	private PublicationResult round ( TestDefinition definition, EventReference after ) {
		Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher = publisherUnderTest();
		String name = publisher.getClass().getSimpleName();
		EventReference outboundBefore = outboundStream().head().orElse(null);
		List<Outcome.PublicationResult> outcomes = new ArrayList<>();
		Exception failure = null;
		try {
			EventQuery query = publisher.eventQuery();
			List<Event<DOMAIN_EVENT_TYPE>> toPublish = domainStream().query(query, after);
			for ( Event<DOMAIN_EVENT_TYPE> event : toPublish ) {
				EventReference head = domainStream().head().orElse(event.reference());
				outcomes.add(Publication.publish(publisher, name, event, head, instance(), reader, outboundStream()));
				handedOverUpTo = event.reference();
			}
		} catch ( Exception e ) {
			failure = e;
		}
		List<Event<OUTBOUND_EVENT_TYPE>> published = outboundStream().query(EventQuery.matchAll(), outboundBefore);
		return new PublicationResult(definition, outcomes, published, failure);
	}

	public class TestDefinition {

		/** Appends domain events, untagged, as fixture data. */
		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> kernel().event(e));
			return this;
		}

		/** Appends a domain event with its tags — the way production tags it, so the publisher finds its entity. */
		public TestDefinition and ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}

		/** Appends domain events, untagged. */
		public TestDefinition and ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			return given(events);
		}

		/**
		 * Publishes every matching domain event the publisher has not been handed yet: the first round all
		 * of them, a later one what was seeded since.
		 */
		public PublicationResult whenPublished ( ) {
			return round(this, handedOverUpTo);
		}

		/**
		 * Hands every matching domain event over again, from the start — the redelivery a crash between
		 * the append and the bookmark, or a failover overlap, causes. What was published the first time
		 * stands, so a correct publisher appends nothing here.
		 */
		public PublicationResult whenRepublished ( ) {
			return round(this, null);
		}
	}

	/** What one round appended to the outbound stream. Chainable; {@link #and()} continues with the next round. */
	public class PublicationResult {

		private final TestDefinition definition;
		private final List<Outcome.PublicationResult> outcomes;
		private final List<Event<OUTBOUND_EVENT_TYPE>> published;
		private final Exception failure;

		private PublicationResult ( TestDefinition definition, List<Outcome.PublicationResult> outcomes, List<Event<OUTBOUND_EVENT_TYPE>> published, Exception failure ) {
			this.definition = definition;
			this.outcomes = outcomes;
			this.published = published;
			this.failure = failure;
		}

		/** Asserts the outbound events this round appended, in order, compared field by field. */
		@SafeVarargs
		public final PublicationResult published ( OUTBOUND_EVENT_TYPE... expected ) {
			noFailure();
			assertEquals(expected.length, published.size(), "number of outbound events published not as expected, was " + published);
			for ( int i = 0; i < expected.length; i++ ) {
				assertCompareObjects(expected[i], published.get(i).data(), "outbound event #%d".formatted(i));
			}
			return this;
		}

		/** Asserts this round appended exactly one outbound event, carrying at least these tags. */
		public PublicationResult published ( OUTBOUND_EVENT_TYPE expected, Tags expectedTags ) {
			noFailure();
			assertEquals(1, published.size(), "one outbound event expected, was " + published);
			assertCompareObjects(expected, published.get(0).data(), "outbound event");
			Event<OUTBOUND_EVENT_TYPE> actual = published.get(0);
			assertTrue(actual.tags().containsAll(expectedTags),
					"outbound event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
			return this;
		}

		/** Asserts this round appended nothing: nothing matched, the publisher published nothing, or all of it was published before. */
		public PublicationResult nothingPublished ( ) {
			noFailure();
			assertEquals(0, published.size(), "no outbound events expected, was " + published);
			return this;
		}

		/** Asserts the publisher threw, with an exception of this type. */
		public PublicationResult failed ( Class<? extends Throwable> expectedType ) {
			CaughtError.of(failure).assertType(expectedType);
			return this;
		}

		/** The outbound events this round appended, for assertions of your own. */
		public List<Event<OUTBOUND_EVENT_TYPE>> outboundEvents ( ) {
			noFailure();
			return published;
		}

		/** What each publication of this round answered, in order. */
		public List<Outcome.PublicationResult> outcomes ( ) {
			return outcomes;
		}

		/** Continues with more seeded events and another round. */
		public TestDefinition and ( ) {
			return definition;
		}

		private void noFailure ( ) {
			if ( failure != null ) {
				fail("the publisher failed: " + failure, failure);
			}
		}
	}

}
