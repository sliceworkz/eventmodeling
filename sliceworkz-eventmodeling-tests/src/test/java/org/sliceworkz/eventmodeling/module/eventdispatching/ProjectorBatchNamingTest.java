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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.RecordingObserver;

/**
 * The event store reports a projector's batches under the name the projector was built with, and the
 * framework's processors all project through one adapter class per kind. Unnamed, every read model would
 * be reported as {@code ReadModelAdapter} and every dispatcher as {@code DispatcherAdapter}; named, each
 * is reported under its component's name — the one its bookmark, its {@code ProcessorStatus} and the
 * bounded-context events use.
 */
public class ProjectorBatchNamingTest extends AbstractMockDomainTest {

	private final RecordingObserver storeObserver = new RecordingObserver();

	@Test
	void theStoreReportsEachProcessorsBatchesUnderItsComponentsName ( ) {
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name("NamingBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.eventStoreObserver(storeObserver);
		builder.readmodel(new MockReadModel("balances", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.dispatcher(new PublishingDispatcher());
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("projected"));
		domain.execute(new MockOutboundCommand("published"), "outbound/1");

		waitBecauseOfEventualConsistency(() -> projectionNames().containsAll(Set.of("balances", "PublishingDispatcher")));

		Set<String> names = projectionNames();
		assertTrue(names.containsAll(Set.of("balances", "PublishingDispatcher")), "each processor under its component's name: " + names);
		assertFalse(names.stream().anyMatch(name -> name.endsWith("Adapter")), "no processor under its adapter's name: " + names);
	}

	private Set<String> projectionNames ( ) {
		return storeObserver.recordings(Observation.ProjectorBatch.class).stream()
				.map(recording -> recording.observation(Observation.ProjectorBatch.class).projection())
				.collect(Collectors.toSet());
	}

	static class PublishingDispatcher implements Dispatcher<MockOutboundEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockOutboundEvent> event ) { }
	}

}
