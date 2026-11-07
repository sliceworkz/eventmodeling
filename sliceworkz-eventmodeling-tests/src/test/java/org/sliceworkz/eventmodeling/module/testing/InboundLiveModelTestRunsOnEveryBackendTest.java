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

import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.UnclaimedInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.testing.InboundLiveModelTest;
import org.sliceworkz.eventmodeling.testing.OutboundLiveModelTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * {@link LiveModelTestRunsOnEveryBackendTest} for the published base of a live read model over the inbound
 * stream: the events it seeds land on the stream the context projects such a read model from, on whichever
 * storage the backend supplied.
 */
public class InboundLiveModelTestRunsOnEveryBackendTest extends InboundLiveModelTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	@Override
	public Class<? extends ReadModel<MockInboundEvent>> getLiveModelClass ( ) {
		return InboundCount.class;
	}

	@Override public Class<MockDomainEvent> domainEventType ( ) { return MockDomainEvent.class; }
	@Override public Class<MockInboundEvent> inboundEventType ( ) { return MockInboundEvent.class; }
	@Override public Class<MockOutboundEvent> outboundEventType ( ) { return MockOutboundEvent.class; }

	@ForEachBackend
	void aLiveModelProjectsWhatCameIn ( ) {
		given()
			.events(new SomeInboundEvent("a"), new UnclaimedInboundEvent("b"), new SomeInboundEvent("c"))
			.then()
			.liveModel(model -> ((InboundCount) model).count())
			.satisfies(count -> assertEquals(3, count));
	}

	public static class InboundCount implements ReadModel<MockInboundEvent> {

		private int count;

		@Override
		public EventFilter eventFilter ( ) {
			return EventFilter.matchAll();
		}

		@Override
		public void when ( Event<MockInboundEvent> event ) {
			count++;
		}

		public int count ( ) {
			return count;
		}
	}

}
