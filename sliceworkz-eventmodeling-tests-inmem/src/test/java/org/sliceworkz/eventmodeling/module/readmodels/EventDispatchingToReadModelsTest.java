/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockBoundedContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.LongLivedReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

public class EventDispatchingToReadModelsTest extends AbstractMockDomainTest {
	
	private EventStorage eventStorage;
	
	private MockReadModel consistentModel;
	private MockReadModel consistentModelOnlyFirstEventType;
	private MockReadModel consistentModelOnlySecondEventType;
	private MockReadModel consistentModelOnlyThirdEventType;
	private MockReadModel eventuallyConsistentSharedModel;
	private MockReadModel eventuallyConsistentSharedModelOnlyFirstEventType;
	private MockReadModel eventuallyConsistentSharedModelOnlySecondEventType;
	private MockReadModel eventuallyConsistentSharedModelOnlyThirdEventType;
	private MockReadModel eventuallyConsistentLocalModel;
	private MockReadModel eventuallyConsistentLocalModelOnlyFirstEventType;
	private MockReadModel eventuallyConsistentLocalModelOnlySecondEventType;
	private MockReadModel eventuallyConsistentLocalModelOnlyThirdEventType;
	
	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.eventStorage = createEventStorage();
		this.consistentModel = new MockReadModel("consistent, all domain events");
		this.consistentModelOnlyFirstEventType = new MockReadModel("consistent, only first domain event", Arrays.asList(new Class[] {FirstDomainEvent.class}));
		this.consistentModelOnlySecondEventType = new MockReadModel("consistent, only second domain event type", Arrays.asList(new Class[] {SecondDomainEvent.class}));
		this.consistentModelOnlyThirdEventType = new MockReadModel("consistent, only third domain event type", Arrays.asList(new Class[] {ThirdDomainEvent.class}));
		this.eventuallyConsistentSharedModel = new MockReadModel("eventually consistent shared model, all domain events");
		this.eventuallyConsistentSharedModelOnlyFirstEventType = new MockReadModel("eventually consistent shared model, only first domain event type", Arrays.asList(new Class[] {FirstDomainEvent.class}));
		this.eventuallyConsistentSharedModelOnlySecondEventType = new MockReadModel("eventually consistent shared model, only second domain event type", Arrays.asList(new Class[] {SecondDomainEvent.class}));
		this.eventuallyConsistentSharedModelOnlyThirdEventType = new MockReadModel("eventually consistent shared model, only third domain event type", Arrays.asList(new Class[] {ThirdDomainEvent.class}));
		this.eventuallyConsistentLocalModel = new MockReadModel("eventually consistent local model, all domain events");
		this.eventuallyConsistentLocalModelOnlyFirstEventType = new MockReadModel("eventually consistent local model, only first domain event type", Arrays.asList(new Class[] {FirstDomainEvent.class}));
		this.eventuallyConsistentLocalModelOnlySecondEventType = new MockReadModel("eventually consistent local model, only second domain event type", Arrays.asList(new Class[] {SecondDomainEvent.class}));
		this.eventuallyConsistentLocalModelOnlyThirdEventType = new MockReadModel("eventually consistent local model, only third domain event type", Arrays.asList(new Class[] {ThirdDomainEvent.class}));
	}
	
	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(eventStorage);
		boundedContext().stop();
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testLiveModelsNotCalledWithExternallyProvidedEvent ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), noConsistentModels(), noEventuallyConsistentSharedModels(), noEventuallyConsistentLocalModels());
		
		// throw in an external event		
		domain.event(new MockDomainEvent.FirstDomainEvent("test"));
		domain.event(new MockDomainEvent.SecondDomainEvent("test"));
		domain.event(new MockDomainEvent.ThirdDomainEvent("test"));
		
		assertEquals(0, MockReadModel.TOTAL_EVENT_COUNT_OVER_INSTANCES(), "live model should not have been called when a domain event happens");
	}

	@Test
	void testLiveModelsNotCalledWithCommandGeneratedEventsSentSeparately ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), noConsistentModels(), noEventuallyConsistentSharedModels(), noEventuallyConsistentLocalModels());
		
		// generate one event at a time via a command
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.FirstDomainEvent("test"))));
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.SecondDomainEvent("test"))));
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.ThirdDomainEvent("test"))));
		
		assertEquals(0, MockReadModel.TOTAL_EVENT_COUNT_OVER_INSTANCES(), "live model should not have been called when a domain event happens");
	}

	@Test
	void testLiveModelsNotCalledWithCommandGeneratedEventsSentInOneTransaction ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), noConsistentModels(), noEventuallyConsistentSharedModels(), noEventuallyConsistentLocalModels());
		
		List<MockDomainEvent> events = new ArrayList<>();
		events.add(new MockDomainEvent.FirstDomainEvent("test"));
		events.add(new MockDomainEvent.SecondDomainEvent("test"));
		events.add(new MockDomainEvent.ThirdDomainEvent("test"));
		
		// generate all events at once in one command
		domain.execute(new MockCommand(events));
		
		assertEquals(0, MockReadModel.TOTAL_EVENT_COUNT_OVER_INSTANCES(), "live model should not have been called when a domain event happens");
	}

	@Test
	void testModelsCallingWhenExternalEventsOccur ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), consistentModels(), eventuallyConsistentSharedModels(), eventuallyConsistentLocalModels());
		
		// throw in an external event		
		domain.event(new MockDomainEvent.FirstDomainEvent("test"));
		domain.event(new MockDomainEvent.SecondDomainEvent("test"));
		domain.event(new MockDomainEvent.ThirdDomainEvent("test"));
		
		assertModelsCallingWhenEventOccurs();
	}
	
	@Test
	void testModelsCallingWhenCommandGeneratedEventsOccurSeparately ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), consistentModels(), eventuallyConsistentSharedModels(), eventuallyConsistentLocalModels());
		
		// generate one event at a time via a command
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.FirstDomainEvent("test"))));
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.SecondDomainEvent("test"))));
		domain.execute(new MockCommand(Collections.singletonList(new MockDomainEvent.ThirdDomainEvent("test"))));

		assertModelsCallingWhenEventOccurs();
	}
	
	@Test
	void testModelsCallingWhenCommandGeneratedEventsOccurInOneTransaction ( ) {
		MockBoundedContext domain = domainWithReadModels(liveModels(), consistentModels(), eventuallyConsistentSharedModels(), eventuallyConsistentLocalModels());
		
		List<MockDomainEvent> events = new ArrayList<>();
		events.add(new MockDomainEvent.FirstDomainEvent("test"));
		events.add(new MockDomainEvent.SecondDomainEvent("test"));
		events.add(new MockDomainEvent.ThirdDomainEvent("test"));
		
		// generate all events at once in one command
		domain.execute(new MockCommand(events));

		assertModelsCallingWhenEventOccurs();
	}

	void assertModelsCallingWhenEventOccurs ( ) {
		
		try {
			// Let the eventually consistent read models keep up
			Thread.sleep(250);
		} catch (InterruptedException e) {
			// no problem
		}

		assertEquals(3, consistentModel.eventCountForThisThread(), "consistent model should have been called from this thread when a domain event happens");
		assertEquals(1, consistentModelOnlyFirstEventType.eventCountForThisThread(), "consistent model should have been called from this thread when a domain event happens, but only if it passes the EventQuery");
		assertEquals(1, consistentModelOnlySecondEventType.eventCountForThisThread(), "consistent model should have been called from this thread when a domain event happens, but only if it passes the EventQuery");
		assertEquals(1, consistentModelOnlyThirdEventType.eventCountForThisThread(), "consistent model should have been called from this thread when a domain event happens, but only if it passes the EventQuery");
		
		assertEquals(0, eventuallyConsistentSharedModel.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentSharedModelOnlyFirstEventType.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentSharedModelOnlySecondEventType.eventCountForThisThread(), "eventually consistent model should nothave been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentSharedModelOnlyThirdEventType.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		
		assertEquals(0, eventuallyConsistentLocalModel.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentLocalModelOnlyFirstEventType.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentLocalModelOnlySecondEventType.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");
		assertEquals(0, eventuallyConsistentLocalModelOnlyThirdEventType.eventCountForThisThread(), "eventually consistent model should not have been called from this thread when a domain event happens");

		// now check the figures - over all threads, so also the eventually consistent ones
		
		assertEquals(3, eventuallyConsistentSharedModel.eventCount(), "eventually consistent model should have been called when a domain event happens and async processing is done");
		assertEquals(1, eventuallyConsistentSharedModelOnlyFirstEventType.eventCount(), "eventually consistent model should have been called when a domain event happens and async processing is done");
		assertEquals(1, eventuallyConsistentSharedModelOnlySecondEventType.eventCount(), "eventually consistent model should have been called when a domain event happens and async processing is done");
		assertEquals(1, eventuallyConsistentSharedModelOnlyThirdEventType.eventCount(), "eventually consistent model should have been called when a domain event happens and async processing is done");

		assertEquals(3, eventuallyConsistentLocalModel.eventCount(), "eventually consistent model should have been called after publish");
		assertEquals(1, eventuallyConsistentLocalModelOnlyFirstEventType.eventCount(), "eventually consistent model should have been called after publish");
		assertEquals(1, eventuallyConsistentLocalModelOnlySecondEventType.eventCount(), "eventually consistent model have been called after publish");
		assertEquals(1, eventuallyConsistentLocalModelOnlyThirdEventType.eventCount(), "eventually consistent model should have been called after publish");

	}
	
	MockBoundedContext domainWithReadModels ( 
			Collection<Class<? extends ReadModelWithMetaData<MockDomainEvent>>> liveModelClasses, 
			Collection<ReadModelWithMetaData<MockDomainEvent>> consistentReadModels,
			Collection<ReadModelWithMetaData<MockDomainEvent>> eventuallyConsistentSharedReadModels,
			Collection<ReadModelWithMetaData<MockDomainEvent>> eventuallyConsistentLocalReadModels ) {
		
		BoundedContextBuilder<MockDomainEvent, Object, Object> builder =
				BoundedContext.newBuilder(MockDomainEvent.class, Object.class, Object.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));

		liveModelClasses.forEach(builder::readmodel);
		consistentReadModels.stream().map(builder::readmodel).forEach(a->a.consistent());
		eventuallyConsistentSharedReadModels.stream().map(builder::readmodel).map(LongLivedReadModelSpecification::shared).forEach(LongLivedReadModelSpecification::eventuallyConsistent);
		eventuallyConsistentLocalReadModels.stream().map(builder::readmodel).map(LongLivedReadModelSpecification::local).forEach(LongLivedReadModelSpecification::eventuallyConsistent);
		
		return buildBoundedContext ( builder );
	}

	
	Collection<Class<? extends ReadModelWithMetaData<MockDomainEvent>>> liveModels ( ) {
		return Arrays.asList(MockReadModel.class);
	}

	Collection<Class<? extends ReadModelWithMetaData<MockDomainEvent>>> noLiveModels ( ) {
		return Collections.emptyList();
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> consistentModels ( ) {
		return Arrays.asList(consistentModel, consistentModelOnlyFirstEventType, consistentModelOnlySecondEventType, consistentModelOnlyThirdEventType);
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> noConsistentModels ( ) {
		return Collections.emptyList();
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> eventuallyConsistentSharedModels ( ) {
		return Arrays.asList(eventuallyConsistentSharedModel, eventuallyConsistentSharedModelOnlyFirstEventType, eventuallyConsistentSharedModelOnlySecondEventType, eventuallyConsistentSharedModelOnlyThirdEventType);
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> eventuallyConsistentLocalModels ( ) {
		return Arrays.asList(eventuallyConsistentLocalModel, eventuallyConsistentLocalModelOnlyFirstEventType, eventuallyConsistentLocalModelOnlySecondEventType, eventuallyConsistentLocalModelOnlyThirdEventType);
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> noEventuallyConsistentSharedModels ( ) {
		return Collections.emptyList();
	}

	Collection<ReadModelWithMetaData<MockDomainEvent>> noEventuallyConsistentLocalModels ( ) {
		return Collections.emptyList();
	}

}
