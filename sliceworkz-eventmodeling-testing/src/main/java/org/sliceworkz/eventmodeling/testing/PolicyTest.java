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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.automation.PolicyRejectionHandling;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.policy.Reaction;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Base for testing {@link Policy policies}: seed the domain stream, let the policy react, and assert on the
 * domain events the commands it issued raised — synchronously, on the test thread.
 * <pre>{@code
 * class CancelSubscriptionsOfCancelledSessionPolicyTest extends PolicyTest<SubscriptionsDomainEvent, SubscriptionsInboundEvent, SubscriptionsOutboundEvent> {
 *
 *     @Override
 *     public Policy<SubscriptionsDomainEvent> policy ( ) {
 *         return new CancelSubscriptionsOfCancelledSessionPolicy();
 *     }
 *
 *     @Test
 *     void theSubscriptionsOfACancelledSessionAreCancelled ( ) {
 *         given(new StudentSubscribedToSession(...), Subscriptions.SESSION.tags(sessionId))
 *             .and(new SessionCancelled(sessionId), Subscriptions.SESSION.tags(sessionId))
 *         .whenReacting()
 *             .events(new SubscriptionCancelled(...));
 *     }
 *
 *     @Test
 *     void reactingAgainRaisesNothing ( ) {
 *         given(...).whenReacting().and().whenRedelivered().noEvents();
 *     }
 * }
 * }</pre>
 * Each round hands the policy the matching domain events it has not been handed yet, in stream order,
 * through the same {@link Reaction} a deployed policy's processor runs — the idempotency key, the tracing
 * ({@code x-caused-by}, the correlation id) and the handling of a reaction repeated are the production ones.
 * The command is executed by this test's bounded context, so it decides on its own decision models over the
 * seeded history exactly as it would deployed.
 *
 * <h2>A rejection stops the round, as a stall does</h2>
 * A deployed policy registered to stall retries a rejected event while everything behind it waits; here the
 * round stops at the rejected event and reports it, so {@code .rejected("...")} asserts the command's "no".
 * A policy registered to skip rejections moves on instead — that is a registration choice, pinned by the
 * framework's own tests, and {@link #onRejection()} lets a test exercise it: override it to answer
 * {@link PolicyRejectionHandling#SKIP}, and a rejection is then the round's outcome rather than its end.
 *
 * <h2>Redelivery</h2>
 * {@link TestDefinition#whenRedelivered()} hands every matching event over again, from the start, as a crash
 * between the append and the bookmark or a failover overlap does. The reaction stands, so a policy and its
 * command are correct when that round raises nothing: {@code .whenRedelivered().noEvents()}.
 *
 * <h2>What this deliberately does not cover</h2>
 * <b>Never register the policy on the builder</b> (via {@link #configure}): the harness drives it itself. The
 * processor around it — leader election, the bookmark and where it starts, the stall and its backoff, an
 * operator's skip, the build-time checks — is framework behaviour, pinned by the framework's own tests.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class PolicyTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	private Policy<DOMAIN_EVENT_TYPE> policyUnderTest;
	private EventReference handedOverUpTo;

	/**
	 * The policy under test. Called once per test method.
	 */
	public abstract Policy<DOMAIN_EVENT_TYPE> policy ( );

	/**
	 * What a rejection does in this test: {@link PolicyRejectionHandling#STALL} by default, which stops the
	 * round at the rejected event. Answer {@link PolicyRejectionHandling#SKIP} to test a policy registered with
	 * {@code skipRejections()}.
	 */
	protected PolicyRejectionHandling onRejection ( ) {
		return PolicyRejectionHandling.STALL;
	}

	/**
	 * Nothing to register: the harness drives the policy itself, and the command it issues needs no
	 * registration. Override to register the live read models or aggregates a scenario needs.
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
	 * Called lazily, so a subclass' {@code policy()} may build on fields its own {@code @BeforeEach} assigns.
	 */
	private Policy<DOMAIN_EVENT_TYPE> policyUnderTest ( ) {
		if ( policyUnderTest == null ) {
			policyUnderTest = policy();
		}
		return policyUnderTest;
	}

	/** One round: every matching domain event after {@code after} handed to the policy, in order, until one fails. */
	private ReactionResult round ( TestDefinition definition, EventReference after ) {
		Policy<DOMAIN_EVENT_TYPE> policy = policyUnderTest();
		String name = policy.policyName();
		EventReference domainBefore = domainStream().head().orElse(null);
		List<Outcome.ReactionResult> outcomes = new ArrayList<>();
		Exception failure = null;
		EventReference stalledOn = null;
		EventQuery query = policy.eventQuery();
		for ( Event<DOMAIN_EVENT_TYPE> event : domainStream().query(query, after) ) {
			if ( domainBefore != null && event.reference().happenedAfter(domainBefore) ) {
				break; // raised during this round: handed over in the next one, as a deployment would
			}
			try {
				outcomes.add(Reaction.react(policy, name, event, onRejection(), instance(), kernel(), domainStream()));
				handedOverUpTo = event.reference();
			} catch ( Exception e ) {
				failure = e;
				stalledOn = event.reference();
				break;
			}
		}
		List<Event<DOMAIN_EVENT_TYPE>> raised = domainBefore == null
				? domainStream().query(EventQuery.matchAll())
				: domainStream().query(EventQuery.matchAll(), domainBefore);
		return new ReactionResult(definition, outcomes, raised, failure, stalledOn);
	}

	public class TestDefinition {

		/** Appends domain events, untagged, as fixture data. */
		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> kernel().event(e));
			return this;
		}

		/** Appends a domain event with its tags — the way production tags it, so the command finds its entity. */
		public TestDefinition and ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}

		/** Appends domain events, untagged. */
		public TestDefinition and ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			return given(events);
		}

		/**
		 * Hands every matching domain event the policy has not been handed yet to it: the first round all of
		 * them, a later one what was seeded since.
		 */
		public ReactionResult whenReacting ( ) {
			return round(this, handedOverUpTo);
		}

		/**
		 * Hands every matching domain event over again, from the start — the redelivery a crash between the
		 * append and the bookmark, or a failover overlap, causes. The reaction stands, so a correct policy
		 * raises nothing here.
		 */
		public ReactionResult whenRedelivered ( ) {
			return round(this, null);
		}
	}

	/** What one round raised on the domain stream. Chainable; {@link #and()} continues with the next round. */
	public class ReactionResult {

		private final TestDefinition definition;
		private final List<Outcome.ReactionResult> outcomes;
		private final List<Event<DOMAIN_EVENT_TYPE>> raised;
		private final Exception failure;
		private final EventReference stalledOn;

		private ReactionResult ( TestDefinition definition, List<Outcome.ReactionResult> outcomes, List<Event<DOMAIN_EVENT_TYPE>> raised, Exception failure, EventReference stalledOn ) {
			this.definition = definition;
			this.outcomes = outcomes;
			this.raised = raised;
			this.failure = failure;
			this.stalledOn = stalledOn;
		}

		/** Asserts the domain events the commands this round issued raised, in order, compared field by field. */
		@SafeVarargs
		public final ReactionResult events ( DOMAIN_EVENT_TYPE... expected ) {
			noFailure();
			assertEquals(expected.length, raised.size(), "number of domain events raised not as expected, was " + raised);
			for ( int i = 0; i < expected.length; i++ ) {
				assertCompareObjects(expected[i], raised.get(i).data(), "domain event #%d".formatted(i));
			}
			return this;
		}

		/** Asserts this round raised exactly one domain event, carrying at least these tags. */
		public ReactionResult event ( DOMAIN_EVENT_TYPE expected, Tags expectedTags ) {
			noFailure();
			assertEquals(1, raised.size(), "one domain event expected, was " + raised);
			assertCompareObjects(expected, raised.get(0).data(), "domain event");
			Event<DOMAIN_EVENT_TYPE> actual = raised.get(0);
			assertTrue(actual.tags().containsAll(expectedTags),
					"domain event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
			return this;
		}

		/**
		 * Asserts this round raised nothing: nothing matched, the policy issued no command, the command's own
		 * rules found nothing to do, or the reaction was repeated and swallowed.
		 */
		public ReactionResult noEvents ( ) {
			noFailure();
			assertEquals(0, raised.size(), "no domain events expected, was " + raised);
			return this;
		}

		/**
		 * Asserts the policy issued no command for any event of this round: every one was let pass.
		 */
		public ReactionResult ignored ( ) {
			noFailure();
			assertTrue(outcomes.stream().allMatch(Outcome.Ignored.class::isInstance), "every event ignored expected, was " + outcomes);
			return noEvents();
		}

		/**
		 * Asserts the command a policy issued was rejected with this message — the round stopped there, as a
		 * stalled policy would, or (with {@link PolicyTest#onRejection()} answering {@code SKIP}) moved past it.
		 */
		public ReactionResult rejected ( String expectedMessage ) {
			if ( failure != null ) {
				CaughtError.of(failure).assertTypeAndMessage(BusinessException.class, expectedMessage);
				return this;
			}
			List<String> reasons = outcomes.stream()
					.filter(Outcome.ReactionRejected.class::isInstance)
					.map(o -> ((Outcome.ReactionRejected) o).reason())
					.toList();
			assertTrue(reasons.contains(expectedMessage), "a rejection '%s' expected, rejections were %s".formatted(expectedMessage, reasons));
			return this;
		}

		/** Asserts the policy failed — stalled — on an event, with an exception of this type. */
		public ReactionResult failed ( Class<? extends Throwable> expectedType ) {
			CaughtError.of(failure).assertType(expectedType);
			return this;
		}

		/** The event the round stopped at because its reaction failed, or {@code null} when none did. */
		public EventReference stalledOn ( ) {
			return stalledOn;
		}

		/** The domain events this round raised, for assertions of your own — their tags include {@code x-caused-by}. */
		public List<Event<DOMAIN_EVENT_TYPE>> domainEvents ( ) {
			noFailure();
			return raised;
		}

		/** What each reaction of this round answered, in order. */
		public List<Outcome.ReactionResult> outcomes ( ) {
			return outcomes;
		}

		/** Continues with more seeded events and another round. */
		public TestDefinition and ( ) {
			return definition;
		}

		private void noFailure ( ) {
			if ( failure != null ) {
				fail("the policy stalled on %s: %s".formatted(stalledOn, failure), failure);
			}
		}
	}

	/** The causation tag a policy leaves on what its command raised, for a test asserting it. */
	public static Tags causedBy ( Event<?> event ) {
		return Tags.of(Tracing.TAG_CAUSED_BY, event.reference().id().value());
	}

}
