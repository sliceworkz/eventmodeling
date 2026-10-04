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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.automation.PolicyRejectionHandling;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.policy.SecondFromFirstPolicy;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.testing.PolicyTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * What a policy's reaction does and what a redelivery does, through the <em>published</em> {@link PolicyTest}
 * base — and that the base runs against every registered event storage. The storage-sensitive halves are the
 * trace a reaction leaves (a lookup on two tags) and the keys the command is executed under.
 */
public class PolicyTestRunsOnEveryBackendTest extends PolicyTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	private Policy<MockDomainEvent> policy = new SecondFromFirstPolicy();
	private PolicyRejectionHandling onRejection = PolicyRejectionHandling.STALL;

	@Override
	public Policy<MockDomainEvent> policy ( ) {
		return policy;
	}

	@Override
	protected PolicyRejectionHandling onRejection ( ) {
		return onRejection;
	}

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@ForEachBackend
	void wheneverItsEventHappensTheCommandItIssuesRaisesItsEventsNamingTheCause ( ) {
		var result = given(new FirstDomainEvent("a"))
		.whenReacting()
			.event(new SecondDomainEvent("a"), Tags.of("item", "a"));

		Event<MockDomainEvent> first = domainStream().query(EventQuery.forTypes(FirstDomainEvent.class)).get(0);
		assertTrue(result.domainEvents().get(0).tags().containsAll(causedBy(first)), "what the command raised names the event reacted to");
		assertEquals("SecondFromFirstPolicy", Tracing.readFrom(result.domainEvents().get(0)).actor());
	}

	@ForEachBackend
	void aRedeliveredEventIsNotReactedToAgain ( ) {
		given(new FirstDomainEvent("a"), Tags.none())
			.and(new FirstDomainEvent("b"))
		.whenReacting()
			.events(new SecondDomainEvent("a"), new SecondDomainEvent("b"))
		.and()
		.whenRedelivered()
			.noEvents();
	}

	/**
	 * A command deciding otherwise on a redelivery — here raising two events where it raised one, so the
	 * keys it would append under were never stored — is still not executed again: the trace the first
	 * reaction left is what counts.
	 */
	@ForEachBackend
	void aRedeliveredEventIsNotReactedToAgainWhateverTheCommandWouldDecideNow ( ) {
		policy = new GrowingPolicy();
		var redelivered = given(new FirstDomainEvent("a"))
		.whenReacting()
			.events(new SecondDomainEvent("a#1"))
		.and()
		.whenRedelivered()
			.noEvents();
		assertTrue(redelivered.outcomes().get(0) instanceof Outcome.AlreadyReacted, "was " + redelivered.outcomes());
	}

	@Test
	void anEventThePolicyDoesNotActOnIsLetPass ( ) {
		given(new FirstDomainEvent("ignore-me"))
		.whenReacting()
			.ignored();
	}

	@Test
	void aRejectionStopsTheRoundAsAStallDoes ( ) {
		var result = given(new FirstDomainEvent("reject-1"))
			.and(new FirstDomainEvent("b"))
		.whenReacting()
			.rejected("rejected reject-1");
		assertEquals(0, result.outcomes().size(), "nothing behind the rejected event was reacted to");
	}

	@Test
	void aPolicySkippingRejectionsMovesPastThem ( ) {
		onRejection = PolicyRejectionHandling.SKIP;
		given(new FirstDomainEvent("reject-1"))
			.and(new FirstDomainEvent("b"))
		.whenReacting()
			.rejected("rejected reject-1")
			.events(new SecondDomainEvent("b"));
	}

	/** Raises one event more each time it is executed: a command that decides otherwise every time. */
	static class GrowingPolicy implements Policy<MockDomainEvent> {

		private int executions;

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forTypes(FirstDomainEvent.class);
		}

		@Override
		public String policyName ( ) {
			return "GrowingPolicy";
		}

		@Override
		public Optional<Command<MockDomainEvent>> react ( Event<MockDomainEvent> event ) {
			int count = ++executions;
			String value = ((FirstDomainEvent) event.data()).value();
			return Optional.of(new Command<MockDomainEvent>() {
				@Override
				public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
					var result = context.noDecisionModels();
					result.raiseEvent(new SecondDomainEvent(value + "#1"), Tags.none());
					for ( int i = 2; i <= count; i++ ) {
						result.raiseEvent(new ThirdDomainEvent(value + "#" + i), Tags.none());
					}
				}
			});
		}
	}

}
