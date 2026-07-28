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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The bounded context must not report itself started before its ephemeral read models have been
 * projected completely — they start empty on every process start, so serving them half-built would
 * hand out wrong answers to whoever calls right after {@code start()}.
 */
public class EphemeralReadModelProjectedOnStartTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final String DOMAIN_PURPOSE = "domain";
	private static final int PRE_EXISTING_EVENT_COUNT = 250;

	private EventStorage eventStorage;

	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.eventStorage = InMemoryEventStorage.newBuilder().build();
	}

	@AfterEach
	protected void tearDown ( ) {
		if ( boundedContext() != null ) {
			boundedContext().stop();
		}
	}

	@Test
	void testEphemeralReadModelsAreProjectedBeforeStartReturns ( ) {
		appendPreExistingEvents();

		MockReadModel firstModel = new MockReadModel("first ephemeral model", ReadModelStorage.EPHEMERAL);
		MockReadModel secondModel = new MockReadModel("second ephemeral model", ReadModelStorage.EPHEMERAL);

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(firstModel).eventuallyConsistent();
		builder.readmodel(secondModel).eventuallyConsistent();

		buildBoundedContext(builder); // builds and starts the bounded context

		// no awaiting here on purpose: start() is expected to have waited for the ephemeral models
		assertEquals(PRE_EXISTING_EVENT_COUNT, firstModel.eventCount(), "ephemeral readmodel should be fully projected when start() returns");
		assertEquals(PRE_EXISTING_EVENT_COUNT, secondModel.eventCount(), "every ephemeral readmodel should be fully projected when start() returns");
	}

	private void appendPreExistingEvents ( ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage);
		EventStream<MockDomainEvent> domainEventStream = eventStore.getEventStream(
				EventStreamId.forContext(CONTEXT_NAME).withPurpose(DOMAIN_PURPOSE), MockDomainEvent.class);

		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>();
		for ( int i = 0; i < PRE_EXISTING_EVENT_COUNT; i++ ) {
			events.add(Event.of(new MockDomainEvent.FirstDomainEvent("event " + i), Tags.none()));
		}
		domainEventStream.append(AppendCriteria.none(), events);
	}

}
