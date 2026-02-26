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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.spi.EventStorage;

public class RenderLiveModelTest extends AbstractMockDomainTest {
	
	private EventStorage rawEventStorage;
	private InvocationCountingEventStorage eventStorage;
	
	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.rawEventStorage = createEventStorage();
		this.eventStorage = new InvocationCountingEventStorage(rawEventStorage);
	}
	
	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(rawEventStorage);
		boundedContext().stop();
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testProjectLiveModel1 ( ) {
		Mock boundedContext = domainWithLiveModel(MockReadModel.class);
		testLiveModelWithDifferentNumberOfEvents( boundedContext, 1);
	}

	@Test
	void testProjectLiveModel2 ( ) {
		Mock boundedContext = domainWithLiveModel(MockReadModel.class);
		testLiveModelWithDifferentNumberOfEvents( boundedContext, 2);
	}

	@Test
	void testProjectLiveModel250 ( ) {
		Mock boundedContext = domainWithLiveModel(MockReadModel.class);
		testLiveModelWithDifferentNumberOfEvents( boundedContext, 250);
	}
	
	@Test
	void testProjectLiveModel1000 ( ) {
		Mock boundedContext = domainWithLiveModel(MockReadModel.class);
		testLiveModelWithDifferentNumberOfEvents( boundedContext, 10000);
	}

	private void testLiveModelWithDifferentNumberOfEvents ( Mock boundedContext, int eventCount ) {
		int expectedQueries = (eventCount - 1) / Projector.Builder.DEFAULT_MAX_EVENTS_PER_QUERY + 2;
		
//		System.out.println("assuming "  + expectedQueries + " queries for " + eventCount + " events");
		
		for ( int i = 0; i < eventCount; i++ ) {
			// throw in an external event		
			boundedContext.event(new MockDomainEvent.FirstDomainEvent("test " + i));
		}
		MockReadModel m = boundedContext.read(MockReadModel.class, "someLiveModel");
		
		assertEquals(eventCount, m.eventCount(), "live model should have seen all events");
		assertEquals(expectedQueries, eventStorage.queriesDone());

//		System.out.println(time + " ms for " + eventCount + " events in " + expectedQueries + " queries in readmodel");
	}
	
	Mock domainWithLiveModel ( Class<? extends ReadModel<MockDomainEvent>> liveModelClass ) {
		
		var builder = BoundedContext.newBuilder(Mock.class)
			.name("UnitTestBoundedContext")
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(liveModelClass).live();
		
		return buildBoundedContext ( builder );
	}

}
