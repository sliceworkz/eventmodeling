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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage;

public class LiveModelSnapshotTest extends AbstractMockDomainTest {

	private EventStorage eventStorage;

	private MockLiveModelSnapshotStorage snapshotStorage = new MockLiveModelSnapshotStorage();

	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		SnapshotLiveModel.VERSION = "v1";
		this.eventStorage = createEventStorage();
	}

	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(eventStorage);
		if ( boundedContext() != null ) {
			boundedContext().stop();
		}
	}

	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}

	public void destroyEventStorage ( EventStorage storage ) {

	}

	@Test
	void testLiveModelWithoutSnapshots ( ) {
		Mock domain = domainWithLiveModel(0);

		for ( int i = 0; i < 100; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}

		SnapshotLiveModel model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(100, model.getCounter());
		assertEquals(0, snapshotStorage.getSaveInvokes());
	}

	@Test
	void testLiveModelSnapshots ( ) {
		Mock domain = domainWithLiveModel(5);

		// Produce 10 events
		for ( int i = 0; i < 10; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}

		// First read: projects all 10 events, saves snapshot (10 >= threshold of 5)
		SnapshotLiveModel model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(1, snapshotStorage.getSaveInvokes());

		// Second read: loads snapshot, replays 0 events on top (no new events)
		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(0, model.getEventsOnTopOfSnapshot());

		// Add 3 more events — below threshold
		for ( int i = 10; i < 13; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}

		// Third read: loads snapshot, replays 3 events on top (3 < 5, no new snapshot)
		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(13, model.getCounter());
		assertEquals(3, model.getEventsOnTopOfSnapshot());
		assertEquals(1, snapshotStorage.getSaveInvokes()); // still 1 from the first save

		// Add 7 more events — now 10 new events since snapshot
		for ( int i = 13; i < 20; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}

		// Fourth read: loads snapshot, replays 10 events on top (10 >= 5, saves new snapshot)
		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(20, model.getCounter());
		assertEquals(10, model.getEventsOnTopOfSnapshot());
		assertEquals(2, snapshotStorage.getSaveInvokes());

		// Fifth read: loads latest snapshot, replays 0 events
		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(20, model.getCounter());
		assertEquals(0, model.getEventsOnTopOfSnapshot());
	}

	@Test
	void testLiveModelSnapshotsChangingVersion ( ) {
		Mock domain = domainWithLiveModel(5);

		// Produce 10 events and read to create first snapshot
		for ( int i = 0; i < 10; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}
		SnapshotLiveModel model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(1, snapshotStorage.getSaveInvokes());

		// Change version — snapshot should not be loaded
		SnapshotLiveModel.VERSION = "v" + UUID.randomUUID().toString();

		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(10, model.getEventsOnTopOfSnapshot()); // full replay since version mismatch
		assertEquals(2, snapshotStorage.getSaveInvokes()); // new snapshot saved with new version

		// Revert version — latest snapshot with original version is still there
		SnapshotLiveModel.VERSION = "v1";
		model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(0, model.getEventsOnTopOfSnapshot()); // snapshot with v1 was loaded
	}

	@Test
	void testLiveModelSnapshotReadOnly ( ) {
		// First, create a domain with readAndWrite to establish a snapshot
		Mock domain = domainWithLiveModelMode(5, "readAndWrite");

		for ( int i = 0; i < 10; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}
		SnapshotLiveModel model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(1, snapshotStorage.getSaveInvokes());
		domain.stop();

		// Now create a new domain with readOnly mode
		Mock domain2 = domainWithLiveModelMode(5, "readOnly");
		model = domain2.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(0, model.getEventsOnTopOfSnapshot()); // snapshot was loaded
		assertEquals(1, snapshotStorage.getSaveInvokes()); // no new save in readOnly
	}

	@Test
	void testLiveModelSnapshotWriteOnly ( ) {
		Mock domain = domainWithLiveModelMode(5, "writeOnly");

		for ( int i = 0; i < 10; i++ ) {
			domain.event(new FirstDomainEvent("test " + i));
		}
		SnapshotLiveModel model = domain.read(SnapshotLiveModel.class, "myModel");
		assertEquals(10, model.getCounter());
		assertEquals(10, model.getEventsOnTopOfSnapshot()); // full replay in writeOnly
		assertEquals(1, snapshotStorage.getSaveInvokes()); // but snapshot was saved
	}


	Mock domainWithLiveModel ( int snapshotAfterEventCount ) {
		return domainWithLiveModelMode(snapshotAfterEventCount, "readAndWrite");
	}

	Mock domainWithLiveModelMode ( int snapshotAfterEventCount, String mode ) {

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));

		if ( snapshotAfterEventCount == 0 ) {
			builder.readmodel(SnapshotLiveModel.class).live();
		} else {
			var snapshotSpec = builder.readmodel(SnapshotLiveModel.class).snapshots(snapshotStorage).eventCountThreshold(snapshotAfterEventCount);
			switch ( mode ) {
				case "readOnly" -> snapshotSpec.readOnly();
				case "writeOnly" -> snapshotSpec.writeOnly();
				default -> snapshotSpec.readAndWrite();
			}
		}

		return buildBoundedContext(builder);
	}

}

class SnapshotLiveModel implements ReadModel<MockDomainEvent>, SnapshotCapable<SnapshotLiveModel.SnapshotData> {

	public static String VERSION = "v1";

	private String name;
	private int counter;
	private int eventsOnTopOfSnapshot;

	public SnapshotLiveModel ( String name ) {
		this.name = name;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
	}

	@Override
	public void when ( MockDomainEvent event ) {
		counter++;
		eventsOnTopOfSnapshot++;
	}

	@Override
	public String readmodelName ( ) {
		return name;
	}

	public int getCounter ( ) {
		return counter;
	}

	public int getEventsOnTopOfSnapshot ( ) {
		return eventsOnTopOfSnapshot;
	}

	// --- SnapshotCapable ---

	@Override
	public SnapshotData takeSnapshot ( ) {
		return new SnapshotData(counter);
	}

	@Override
	public void fromSnapshot ( SnapshotData snapshot ) {
		this.counter = snapshot.counter;
		this.eventsOnTopOfSnapshot = 0;
	}

	@Override
	public String version ( ) {
		return VERSION;
	}

	public static class SnapshotData {
		private int counter;

		public SnapshotData ( ) {
		}

		public SnapshotData ( int counter ) {
			this.counter = counter;
		}

		public SnapshotData clone ( ) {
			return new SnapshotData(counter);
		}
	}

}

class MockLiveModelSnapshotStorage implements SnapshotStorage<SnapshotLiveModel.SnapshotData> {

	private int saveInvokes;
	private Map<String, SnapshotRecord<SnapshotLiveModel.SnapshotData>> snapshots = new HashMap<>();

	@Override
	public Optional<SnapshotRecord<SnapshotLiveModel.SnapshotData>> load ( String key, String version ) {
		return Optional.ofNullable(snapshots.get(key + version));
	}

	@Override
	public void save ( String key, String version, SnapshotLiveModel.SnapshotData snapshot, EventReference lastEventReference ) {
		saveInvokes++;
		snapshots.put(key + version, new SnapshotRecord<>(snapshot.clone(), lastEventReference));
	}

	public int getSaveInvokes ( ) {
		return saveInvokes;
	}

}
