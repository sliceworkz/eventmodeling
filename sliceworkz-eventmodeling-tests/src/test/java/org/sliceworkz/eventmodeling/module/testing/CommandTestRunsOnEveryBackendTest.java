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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.CommandTest;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * That a test written against the <em>published</em> {@link CommandTest} runs against every
 * registered event storage, not only the in-memory one.
 * <p>
 * This is the module's own copy of what a framework user gets. The base classes in
 * {@code sliceworkz-eventmodeling-testing} used to build an {@code InMemoryEventStorage} of their
 * own, so a user extending them had no way to reach the matrix this suite runs on — while the
 * framework's own {@code mock.boundedcontext.AbstractBoundedContextTest}, which is test-scoped and
 * ships to nobody, did. The two are now the same mechanism, and this test is what keeps them so:
 * it fails on the in-memory backend alone if the published base ever stops honouring
 * {@link ForEachBackend}.
 * <p>
 * The plain {@code @Test} below is the other half of the contract — every test written before the
 * matrix existed keeps running, once, against the in-memory store, with nothing to change.
 */
public class CommandTestRunsOnEveryBackendTest extends CommandTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

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
	void aCommandRaisesItsEventsOnEveryBackend ( ) {
		given()
			.when(new MockCommand(List.of(new FirstDomainEvent("a"), new SecondDomainEvent("b"))))
			.then()
			.events(new FirstDomainEvent("a"), new SecondDomainEvent("b"));
	}

	@ForEachBackend
	void givenEventsAreReadBackFromTheBackendsOwnStorage ( ) {
		given(new FirstDomainEvent("history"))
			.when(new MockCommand(List.of(new SecondDomainEvent("new"))))
			.then()
			.event(new SecondDomainEvent("new"));

		// the seeded event and the raised one are both in the store the backend supplied, which is
		// what proves the bounded context was built over it rather than over one of its own
		assertTrue(eventStore().getEventStream(eventStreamId(), MockDomainEvent.class)
				.query(EventQuery.matchAll()).count() >= 2);
	}

	/**
	 * A plain {@code @Test} still runs, once, against the in-memory store — no backend registered,
	 * nothing configured, exactly as before the matrix was reachable from here.
	 */
	@Test
	void aPlainTestStillRunsAgainstTheInMemoryStore ( ) {
		assertNotNull(eventStorage());

		given()
			.when(new MockCommand(List.of(new FirstDomainEvent("in-memory"))))
			.then()
			.event(new FirstDomainEvent("in-memory"));
	}

}
