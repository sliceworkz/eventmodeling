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
package org.sliceworkz.eventmodeling.module.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.aggregates.MockAggregate.MockAggregateData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

public class AggregateCapabilityTest  extends AbstractMockDomainTest {

	private MockSnapshotStorage snapshotStorage = new MockSnapshotStorage();

	@ForEachBackend
	void testAggregate ( ) {
		
		EventStream<MockDomainEvent> allStream = EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.anyContext().withPurpose("domain"));
		
		Mock domain = domainWithAggregate(List.of(MockAggregate.class), 0);
		
		MockAggregate bo123 = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		MockAggregate bo456 = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "456"));
		MockAggregate bo123Again = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		
		assertEquals(0,bo123.getCounter());
		assertEquals(0,bo456.getCounter());
		assertEquals(0,bo123Again.getCounter());
		
		bo123.doSomething();
		
		List<? extends Event<MockDomainEvent>> all = allStream.query(EventQuery.matchAll()).toList();
		assertEquals(1, all.size());
		assertTrue(all.get(0).tags().containsAll(Tags.of("businessObject", "123")));
		
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
	
	/** Every event handed to {@code raiseEvents} is appended, and the aggregate is told about each. */
	@ForEachBackend
	void testAggregateRaisingSeveralEventsAtOnce ( ) {

		EventStream<MockDomainEvent> allStream = EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.anyContext().withPurpose("domain"));

		Mock domain = domainWithAggregate(List.of(MockAggregate.class), 0);

		MockAggregate bo = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		bo.doThreeThings();

		List<? extends Event<MockDomainEvent>> all = allStream.query(EventQuery.matchAll()).toList();
		assertEquals(3, all.size(), "all three raised events should have been appended");
		all.forEach(event -> assertTrue(event.tags().containsAll(Tags.of("businessObject", "123"))));

		assertEquals(3, bo.getCounter(), "the aggregate should have been told about each appended event");

		// and the appender's lock reference moved with them, so the same instance can carry on
		bo.doSomething();
		assertEquals(4, bo.getCounter());
		assertEquals(4, allStream.query(EventQuery.matchAll()).toList().size());
	}

	@ForEachBackend
	void testAggregateSnapshots ( ) {
		Mock domain = domainWithAggregate(List.of(MockAggregate.class), 5);
		
		MockAggregate a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		
		assertEquals(0,a.getCounter());
		for ( int i = 0; i < 500; i++ ) {			
			assertEquals(i,a.getCounter());
			a.doSomething(i);

			assertEquals(i+1,a.getCounter());
			a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

			System.err.println(a.getCounterOnTopOfSnapshot());
			assertEquals((i+1)%5, a.getCounterOnTopOfSnapshot()); // we expect only 1 to 4 events to be loaded each time on top of the snapshot, 1 with the first event (as there is no snapshot then)
			
			assertEquals(i+1,a.getCounter());
		}
		
		assertEquals(500, a.getCounter());
		assertEquals(100, snapshotStorage.getSaveInvokes());
		assertEquals(0, a.getCounterOnTopOfSnapshot()); // last append should also trigger a saveSnapshot
	}

	/**
	 * An aggregate that is kept and used, rather than re-loaded before every change, still snapshots at
	 * the rate it was configured for: the events it raises itself count towards the threshold.
	 */
	@ForEachBackend
	void testAggregateSnapshotsWhileItIsHeldRatherThanReloaded ( ) {
		Mock domain = domainWithAggregate(List.of(MockAggregate.class), 5);

		MockAggregate a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

		for ( int i = 0; i < 20; i++ ) {
			a.doSomething(i); // same instance throughout: never re-loaded
		}

		assertEquals(20, a.getCounter());
		assertEquals(4, snapshotStorage.getSaveInvokes(), "20 events at a threshold of 5 is 4 snapshots");
	}

	@ForEachBackend
	void testAggregateSnapshotsChangingVersion ( ) {
		Mock domain = domainWithAggregate(List.of(MockAggregate.class), 5);
		
		MockAggregate a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		
		assertEquals(0,a.getCounter());
		for ( int i = 0; i < 500; i++ ) {
			assertEquals(i,a.getCounter());
			a.doSomething(i);

			assertEquals(i+1,a.getCounter());

			MockAggregate.VERSION = "v" + UUID.randomUUID().toString();  // change version before load, so the saved snapshot cannot be used 
			a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

			assertEquals(i+1, a.getCounterOnTopOfSnapshot()); // since the version of the aggregate data changes each time, no reuse is possible
			
			assertEquals(i+1,a.getCounter());
		}
		
		assertEquals(500, a.getCounter());
		assertEquals(497, snapshotStorage.getSaveInvokes()); // saving only started with 5th event, and then each time since the snapshot was never loaded (due to version)
		assertEquals(500, a.getCounterOnTopOfSnapshot());
		
		a.doSomething(500); // one more time, without changing the version
		a = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, a.getCounterOnTopOfSnapshot()); // since the version didn't change now, we can load the latest one and reuse that
		assertEquals(501,a.getCounter());
	}

	@ForEachBackend
	void testUnregisteredAggregate ( ) {
		
		Mock domain = domainWithAggregate(Collections.emptyList(), 0);
		
		IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, ()->domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123")));
		assertEquals("aggregate class 'class org.sliceworkz.eventmodeling.module.aggregates.MockAggregate' not registered in bounded context 'UnitTestBoundedContext'", iae.getMessage());
	}		

	@ForEachBackend
	void testDuplicatedAggregate ( ) {
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->domainWithAggregate(List.of(MockAggregate.class, MockAggregate.class), 0));
		assertEquals("duplicate aggregate registration for 'class org.sliceworkz.eventmodeling.module.aggregates.MockAggregate'", e.getMessage());
	}		

	Mock domainWithAggregate ( 
			List<Class<? extends Aggregate<MockDomainEvent>>> aggregateClasses, int snapshotAfterEventCount ) { 
		
		var builder =
				BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));

		if ( snapshotAfterEventCount == 0 ) {
			aggregateClasses.forEach(aggregateClass->builder.aggregate(aggregateClass));
		} else {
			aggregateClasses.forEach(aggregateClass->builder.aggregate(aggregateClass).snapshots(snapshotStorage).eventCountThreshold(snapshotAfterEventCount).readAndWrite());
		}
		
		return buildBoundedContext ( builder );
	}

}

class MockAggregate implements Aggregate<MockDomainEvent>, SnapshotCapable<MockAggregateData> {

	public static String VERSION = "v1";

	private MockAggregateData data = new MockAggregateData();
	private AggregateContext<MockDomainEvent> ctx;
	
	private int counterOnTopOfSnapshot;
	
	public void doSomething ( ) {
		doSomething(0);
	}
	
	public void doSomething ( int number ) {
		ctx.raiseEvent(new FirstDomainEvent("test " + number));
	}

	public void doThreeThings ( ) {
		ctx.raiseEvents(List.of(new FirstDomainEvent("one"), new FirstDomainEvent("two"), new FirstDomainEvent("three")));
	}
	
	public int getCounter ( ) {
		return data.counter;
	}
	
	@Override
	public void when(MockDomainEvent event) {
		counterOnTopOfSnapshot++;
		data.counter++;
	}

	@Override
	public void setContext(AggregateContext<MockDomainEvent> aggregateContext) {
		this.ctx = aggregateContext; 
		
	}
	
	void methodToUpdateFromStream ( ) {
		ctx.updateFromStream();
	}

	public int getCounterOnTopOfSnapshot ( ) {
		return counterOnTopOfSnapshot;
	}
	
	@Override
	public MockAggregateData takeSnapshot() {
		return data.clone();
	}

	@Override
	public void fromSnapshot(MockAggregateData snapshot) {
		this.data = snapshot.clone();
	}

	public static class MockAggregateData {
		private int counter;
		
		public MockAggregateData ( ) {
			
		}
		
		public MockAggregateData ( int counter ) {
			this.counter = counter;
		}
		
		public MockAggregateData clone ( ) {
			MockAggregateData result = new MockAggregateData();
			result.counter = this.counter;
			return result;
		}
	}

	@Override
	public String version() {
		return VERSION;
	}
}

class MockSnapshotStorage implements SnapshotStorage<MockAggregateData> {

	private int saveInvokes;
	private Map<String,SnapshotRecord<MockAggregateData>> snapshots = new HashMap<>();
	
	@Override
	public Optional<SnapshotRecord<MockAggregateData>> load(String key, String version) {
		return Optional.ofNullable(snapshots.get(key + version));
	}

	@Override
	public void save(String key, String version, MockAggregateData snapshot, EventReference lastEventReference) {
		saveInvokes++;
		snapshots.put(key + version, new SnapshotRecord<>(snapshot, lastEventReference));
	}
	
	public int getSaveInvokes ( ) {
		return saveInvokes;
	}
	
};