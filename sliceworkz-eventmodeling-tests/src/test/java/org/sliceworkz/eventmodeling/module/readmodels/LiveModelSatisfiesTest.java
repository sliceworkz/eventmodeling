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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.testing.LiveModelTest;

/**
 * Tests demonstrating the {@code .satisfies()} method on {@link LiveModelTest}.
 * <p>
 * The {@code satisfies()} method allows custom assertion logic on the live model projection,
 * providing more flexibility than {@code .is()} for cases where you need range checks,
 * partial matching, or multiple assertions on the projected result.
 * <p>
 * Usage pattern:
 * <pre>
 *   given()
 *     .events(event1, event2)
 *     .when("modelName")
 *     .then()
 *     .liveModel(model -> ((MyModel) model).someProperty())
 *     .satisfies(value -> assertEquals(expected, value));
 * </pre>
 */
public class LiveModelSatisfiesTest extends LiveModelTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	@Override
	public Class<? extends ReadModelWithMetaData<MockDomainEvent>> getLiveModelClass() {
		return MockReadModel.class;
	}

	@Override
	public Class<MockDomainEvent> domainEventType() {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType() {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType() {
		return MockOutboundEvent.class;
	}

	@Test
	void satisfies_withEventCount() {
		given()
			.events(
				new FirstDomainEvent("a"),
				new FirstDomainEvent("b"),
				new FirstDomainEvent("c")
			)
			.when("myLiveModel")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> assertEquals(3, count));
	}

	@Test
	void satisfies_withZeroEvents() {
		given()
			.when("myLiveModel")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> assertEquals(0, count));
	}

	@Test
	void satisfies_withMixedEventTypes() {
		given()
			.events(
				new FirstDomainEvent("first"),
				new SecondDomainEvent("second"),
				new FirstDomainEvent("another-first")
			)
			.when("myLiveModel")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> {
				int eventCount = (int) count;
				assertTrue(eventCount > 0, "should have processed at least one event");
				assertEquals(3, eventCount, "should have processed all event types");
			});
	}

	@Test
	void satisfies_withRangeAssertion() {
		given()
			.events(
				new FirstDomainEvent("one"),
				new FirstDomainEvent("two"),
				new FirstDomainEvent("three"),
				new FirstDomainEvent("four"),
				new FirstDomainEvent("five")
			)
			.when("myLiveModel")
			.then()
			.liveModel(model -> ((MockReadModel) model).eventCount())
			.satisfies(count -> {
				int eventCount = (int) count;
				assertTrue(eventCount >= 5, "should have at least 5 events");
				assertTrue(eventCount < 100, "should not have excessive events");
			});
	}

	@Test
	void satisfies_withModelNameAssertion() {
		given()
			.events(new FirstDomainEvent("test"))
			.when("myLiveModel")
			.then()
			.liveModel(model -> ((MockReadModel) model).name())
			.satisfies(name -> assertEquals("myLiveModel", name));
	}

}
