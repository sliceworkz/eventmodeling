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
package org.sliceworkz.eventmodeling.module.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockBoundedContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.module.aggregates.MockAggregate.MockAggregateData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class AggregateCapabilityTest  extends AbstractMockDomainTest {
	
	private EventStorage eventStorage;
	
	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.eventStorage = createEventStorage();
	}
	
	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(eventStorage);
		if (boundedContext()!= null) {
			boundedContext().stop();
		}
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testAggregate ( ) {
		
		EventStream<MockDomainEvent> allStream = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.anyContext().withPurpose("domain"));
		
		MockBoundedContext domain = domainWithAggregate(List.of(MockAggregate.class));
		
		MockAggregate bo123 = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		MockAggregate bo456 = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "456"));
		MockAggregate bo123Again = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		
		assertEquals(0,bo123.getCounter());
		assertEquals(0,bo456.getCounter());
		assertEquals(0,bo123Again.getCounter());
		
		bo123.doSomething();
		
		List<? extends Event<MockDomainEvent>> all = allStream.query(EventQuery.matchAll()).toList();
		assertEquals(1, all.size());
		assertEquals(Tags.of("businessObject", "123"), all.get(0).tags());
		
		assertEquals(1,bo123.getCounter());
		assertEquals(0,bo456.getCounter());
		assertEquals(0,bo123Again.getCounter());
		
		bo123.doSomething();
		bo456.doSomething();
		
		assertEquals(2,bo123.getCounter());
		assertEquals(1,bo456.getCounter());
		assertEquals(0,bo123Again.getCounter());
		
		MockAggregateData snapshot = bo123.takeSnapshot();
		
		bo123.doSomething();
		
		assertEquals(3,bo123.getCounter());
		assertEquals(1,bo456.getCounter());
		assertEquals(0,bo123Again.getCounter());

		assertThrows(OptimisticLockingException.class, ()-> bo123Again.doSomething());
		assertThrows(OptimisticLockingException.class, ()-> bo123Again.doSomething()); // second time as well
		bo123Again.methodToUpdateFromStream();
		bo123Again.doSomething(); // should be OK now
		assertThrows(OptimisticLockingException.class, ()-> bo123.doSomething()); // this should now be behind

		MockAggregate bo123YetAgain = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(4, bo123YetAgain.getCounter());
		
		bo123YetAgain.doSomething();
		assertEquals(5, bo123YetAgain.getCounter());
		
		assertThrows(OptimisticLockingException.class, ()-> bo123.doSomething()); // this should be behind
		assertThrows(OptimisticLockingException.class, ()-> bo123Again.doSomething()); // this should be behind
		
		
		MockAggregate bo123FromSnapshot = new MockAggregate();
		bo123FromSnapshot.fromSnapshot(snapshot);
		assertEquals(2,bo123FromSnapshot.getCounter());
		assertThrows(OptimisticLockingException.class, ()-> bo123.doSomething()); // this should be behind
	}
	
	@Test
	void testUnregisteredAggregate ( ) {
		
		MockBoundedContext domain = domainWithAggregate(Collections.emptyList());
		
		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class, ()->domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123")));
		assertEquals(InvocationTargetException.class, e.getCause().getClass());
		assertEquals(IllegalArgumentException.class, e.getCause().getCause().getClass());
		IllegalArgumentException iae = (IllegalArgumentException) e.getCause().getCause();
		assertEquals("aggregate class 'class org.sliceworkz.eventmodeling.module.aggregates.MockAggregate' not registered in bounded context 'UnitTestBoundedContext'", iae.getMessage());
	}		

	@Test
	void testDuplicatedAggregate ( ) {
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->domainWithAggregate(List.of(MockAggregate.class, MockAggregate.class)));
		assertEquals("duplicate aggregate registration for 'class org.sliceworkz.eventmodeling.module.aggregates.MockAggregate'", e.getMessage());
	}		
	
	
	MockBoundedContext domainWithAggregate ( 
			List<Class<? extends Aggregate<MockDomainEvent>>> aggregateClasses ) { 
		
		BoundedContextBuilder<MockDomainEvent, MockInboundEvent, MockOutboundEvent> builder =
				BoundedContext.newBuilder(MockDomainEvent.class, MockInboundEvent.class, MockOutboundEvent.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));

		aggregateClasses.forEach(builder::aggregate);
		
		return buildBoundedContext ( builder );
	}

}

class MockAggregate implements Aggregate<MockDomainEvent>, SnapshotCapable<MockAggregateData> {

	private MockAggregateData data = new MockAggregateData();
	private AggregateContext<MockDomainEvent> ctx;
	
	public void doSomething ( ) {
		ctx.raiseEvent(new FirstDomainEvent("test"));
	}
	
	public int getCounter ( ) {
		return data.counter;
	}
	
	@Override
	public void when(MockDomainEvent event) {
		data.counter++;
	}

	@Override
	public void setContext(AggregateContext<MockDomainEvent> aggregateContext) {
		this.ctx = aggregateContext; 
		
	}
	
	void methodToUpdateFromStream ( ) {
		ctx.updateFromStream();
	}

	
	
	@Override
	public MockAggregateData takeSnapshot() {
		return data.clone();
	}

	@Override
	public void fromSnapshot(MockAggregateData snapshot) {
		this.data = snapshot;
	}
	
	
	
	
	public static class MockAggregateData {
		private int counter;
		
		public MockAggregateData clone ( ) {
			MockAggregateData result = new MockAggregateData();
			result.counter = this.counter;
			return result;
		}
	}
}
