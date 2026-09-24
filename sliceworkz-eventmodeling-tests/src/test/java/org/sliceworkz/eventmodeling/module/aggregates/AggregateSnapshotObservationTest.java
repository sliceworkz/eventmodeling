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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.snapshots.SnapshotObservations;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver.Recording;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The snapshot storage calls of an aggregate are observed: hits and writes by version, misses by the
 * reason the storage gives, failures reported before the throw goes on, and every call nested in the
 * aggregate load or append it belongs to. The miss reason is the load-bearing part — a bumped snapshot
 * version means a full replay on every load, and without {@link MissReason#VERSION_MISMATCH} that is
 * indistinguishable from a key that was never snapshotted.
 */
public class AggregateSnapshotObservationTest extends AbstractMockDomainTest {

	private ClassifyingSnapshotStorage snapshotStorage;
	private RecordingBoundedContextObserver observer;
	private SnapshotObservations snapshots;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		MeteredAggregate.VERSION = "v1";
		snapshotStorage = new ClassifyingSnapshotStorage();
		observer = new RecordingBoundedContextObserver();
		snapshots = new SnapshotObservations(observer);
	}

	/** A load that finds nothing, a save, a load that hits, and a load after a version bump are each told apart. */
	@Test
	void hitsMissesAndWritesAreObservedByVersionAndReason ( ) {
		Mock domain = domainWithAggregate();

		// nothing stored yet: the load misses, and the storage says why
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, snapshots.missed(MissReason.ABSENT, "v1"));
		assertEquals(1, snapshots.loads().size());
		assertEquals(0, snapshots.saves().size());

		// two events reach the threshold of 2: one snapshot is written, under the current version
		aggregate.doSomething();
		aggregate.doSomething();
		assertEquals(1, snapshots.written("v1"));
		assertEquals(1, snapshots.saves().size());

		// reload: the snapshot is found
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, snapshots.found("v1"));

		// a bumped version misses although a snapshot is stored -- the reason says so
		MeteredAggregate.VERSION = "v2";
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, snapshots.missed(MissReason.VERSION_MISMATCH, "v2"));
		assertEquals(1, snapshots.found("v1"), "the hit is untouched by the mismatch");

		assertEquals(List.of(), observer.violations());
	}

	/** A storage that does not override classifyMiss still has its misses reported, without a reason. */
	@Test
	void aStorageWithoutMissClassificationReportsMissesAsUnknown ( ) {
		snapshotStorage.classifies = false;

		Mock domain = domainWithAggregate();
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		assertEquals(1, snapshots.missed(MissReason.UNKNOWN, "v1"));
	}

	/** A classification that throws never fails the read: the miss is reported as unknown and the load goes on. */
	@Test
	void aThrowingClassificationIsContainedAndReportedAsUnknown ( ) {
		snapshotStorage.failClassification = true;

		Mock domain = domainWithAggregate();
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		assertEquals(0, aggregate.getCounter(), "the aggregate loads normally");
		assertEquals(1, snapshots.missed(MissReason.UNKNOWN, "v1"));
	}

	/** A load or save that throws fails its scope, and the throw still reaches the caller. */
	@Test
	void storageFailuresFailTheirScope ( ) {
		Mock domain = domainWithAggregate();

		snapshotStorage.failLoads = true;
		assertThrows(IllegalStateException.class, () -> domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123")));
		assertEquals(1, snapshots.failed(snapshots.loads()));
		assertEquals(1, snapshots.loads().size(), "the failing load is still observed");
		observer.last(Observation.AggregateLoad.class).failure().orElseThrow(); // and so fails the load it was part of

		snapshotStorage.failLoads = false;
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		snapshotStorage.failSaves = true;
		aggregate.doSomething();
		assertThrows(IllegalStateException.class, () -> aggregate.doSomething()); // second event reaches the threshold, the save throws
		assertEquals(1, snapshots.failed(snapshots.saves()));
		assertEquals(0, snapshots.written("v1"), "a failed save is not a write");

		assertEquals(List.of(), observer.violations());
	}

	/** A snapshot load nests in the aggregate load it is part of, and the load reports the base it started from. */
	@Test
	void aSnapshotLoadNestsInTheAggregateLoad ( ) {
		Mock domain = domainWithAggregate();

		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		aggregate.doSomething();
		aggregate.doSomething();
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		Recording load = observer.last(Observation.AggregateLoad.class);
		Recording snapshotLoad = observer.last(Observation.SnapshotLoad.class);
		assertSame(load, snapshotLoad.parent().orElseThrow());
		assertEquals(Observation.SnapshotOwner.AGGREGATE, snapshotLoad.observation(Observation.SnapshotLoad.class).owner());
		assertEquals("MeteredAggregate", snapshotLoad.observation(Observation.SnapshotLoad.class).component());

		Outcome.AggregateLoaded loaded = load.outcome(Outcome.AggregateLoaded.class);
		assertEquals(snapshotLoad.outcome(Outcome.SnapshotFound.class).at(), loaded.startedAfter().orElseThrow(), "restored from the snapshot");
		assertEquals(0, loaded.eventsStreamed(), "with nothing to replay on top of it");
	}

	private Mock domainWithAggregate ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.observer(observer)
				.instance(InstanceFactory.determine("unittests"));
		builder.aggregate(MeteredAggregate.class).snapshots(snapshotStorage).eventCountThreshold(2).readAndWrite();
		return buildBoundedContext(builder);
	}

}

class MeteredAggregate implements Aggregate<MockDomainEvent>, SnapshotCapable<MeteredAggregate.Data> {

	public static String VERSION = "v1";

	private Data data = new Data();
	private AggregateContext<MockDomainEvent> ctx;

	public void doSomething ( ) {
		ctx.raiseEvent(new FirstDomainEvent("test"));
	}

	public int getCounter ( ) {
		return data.counter;
	}

	@Override
	public void when ( Event<MockDomainEvent> event ) {
		data.counter++;
	}

	@Override
	public void setContext ( AggregateContext<MockDomainEvent> aggregateContext ) {
		this.ctx = aggregateContext;
	}

	@Override
	public Data takeSnapshot ( ) {
		return data.clone();
	}

	@Override
	public void fromSnapshot ( Data snapshot ) {
		this.data = snapshot.clone();
	}

	@Override
	public String version ( ) {
		return VERSION;
	}

	public static class Data {
		int counter;

		public Data clone ( ) {
			Data result = new Data();
			result.counter = this.counter;
			return result;
		}
	}

}

/** Keeps one snapshot per key and knows why a load missed — with switches for every failure mode. */
class ClassifyingSnapshotStorage implements SnapshotStorage<MeteredAggregate.Data> {

	boolean classifies = true;
	boolean failClassification;
	boolean failLoads;
	boolean failSaves;

	private final Map<String, String> versionByKey = new HashMap<>();
	private final Map<String, SnapshotRecord<MeteredAggregate.Data>> snapshotByKey = new HashMap<>();

	@Override
	public Optional<SnapshotRecord<MeteredAggregate.Data>> load ( String key, String version ) {
		if ( failLoads ) {
			throw new IllegalStateException("snapshot storage cannot load");
		}
		return version.equals(versionByKey.get(key)) ? Optional.ofNullable(snapshotByKey.get(key)) : Optional.empty();
	}

	@Override
	public void save ( String key, String version, MeteredAggregate.Data snapshot, EventReference lastEventReference ) {
		if ( failSaves ) {
			throw new IllegalStateException("snapshot storage cannot save");
		}
		versionByKey.put(key, version);
		snapshotByKey.put(key, new SnapshotRecord<>(snapshot.clone(), lastEventReference));
	}

	@Override
	public MissReason classifyMiss ( String key, String version ) {
		if ( failClassification ) {
			throw new IllegalStateException("snapshot storage cannot classify");
		}
		if ( !classifies ) {
			return SnapshotStorage.super.classifyMiss(key, version);
		}
		return versionByKey.containsKey(key) ? MissReason.VERSION_MISMATCH : MissReason.ABSENT;
	}

}
