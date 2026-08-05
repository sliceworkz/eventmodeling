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
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.testing.LiveModelTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The same as {@link CommandTestRunsOnEveryBackendTest} for the read side: a live model projected
 * through the published {@link LiveModelTest} reads the events back from whichever storage the
 * backend supplied.
 * <p>
 * Worth having separately from the command one, because a live model is projected by querying the
 * stream at read time rather than by the append path — so this is what would catch a base class
 * that built the bounded context over the backend's storage while leaving the read side pointed at
 * a store of its own.
 */
public class LiveModelTestRunsOnEveryBackendTest extends LiveModelTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	@Override
	public Class<? extends ReadModelWithMetaData<MockDomainEvent>> getLiveModelClass ( ) {
		return MockReadModel.class;
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
	void aLiveModelProjectsEveryEventOnEveryBackend ( ) {
		given()
			.events(new FirstDomainEvent("a"), new SecondDomainEvent("b"), new FirstDomainEvent("c"))
			.when("liveModelOnEveryBackend")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> assertEquals(3, count));
	}

	@ForEachBackend
	void aLiveModelOverAnEmptyStoreIsEmptyOnEveryBackend ( ) {
		given()
			.when("liveModelOnEveryBackend")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> assertEquals(0, count));
	}

}
