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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CUSTOMER;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CustomerId;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;
import org.sliceworkz.eventmodeling.testing.CachingReadModelTest;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * This repository's copy of what a user of the published {@link CachingReadModelTest} gets: the read
 * model loaded on a miss, a held key behind until the test says the projector delivered, current after,
 * on every backend — and the two read assertions failing when the read went the other way.
 */
public class CachingReadModelTestRunsOnEveryBackendTest
		extends CachingReadModelTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent, CustomerId, List<String>> {

	private static final CustomerId C1 = CUSTOMER.id("c1");

	@Override
	public CachingReadModel<MockDomainEvent, CustomerId, List<String>> readModel ( ) {
		return new CustomerValuesReadModel();
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
	void aHeldKeyIsBehindUntilTheProjectorDeliversAndCurrentAfter ( ) {
		given()
			.event(value("a"), tagged(C1))
			.whenRead(C1).loaded().is(List.of("a"))
		.and()
			.event(value("b"), tagged(C1))
			.whenRead(C1).fromMemory().is(List.of("a"))
		.and()
			.projected()
			.whenRead(C1).fromMemory().is(List.of("a", "b"));

		assertEquals(1, readModelUnderTest().statistics().misses(), "loaded once, kept current since");
	}

	@Test
	void theReadAssertionsFailWhenTheReadWentTheOtherWay ( ) {
		var scenario = given().event(value("a"), tagged(C1));

		assertThrows(AssertionFailedError.class, () -> scenario.whenRead(C1).fromMemory());
		assertThrows(AssertionFailedError.class, () -> scenario.whenRead(C1).loaded());
		assertThrows(AssertionFailedError.class, () -> scenario.whenRead(C1).is(List.of("something else")));
	}

	private static FirstDomainEvent value ( String value ) {
		return new FirstDomainEvent(value);
	}

	private static Tags tagged ( CustomerId customer ) {
		return Tags.of(CUSTOMER.tag(customer));
	}

}
