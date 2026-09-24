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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.snapshots.SnapshotObservations;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver.Recording;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * The live model snapshot path is observed through the same {@code ObservedSnapshots} as the aggregate
 * one, as {@link Observation.SnapshotOwner#LIVE_MODEL}: hits and writes by version, misses by reason,
 * each storage call a scope of its own nested in the live model read — so what the storage costs is kept
 * apart from the read around it, which also contains the event replay.
 */
public class LiveModelSnapshotObservationTest extends AbstractMockDomainTest {

	private ClassifyingLiveModelSnapshotStorage snapshotStorage;
	private RecordingBoundedContextObserver observer;
	private SnapshotObservations snapshots;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		MeteredLiveModel.VERSION = "v1";
		snapshotStorage = new ClassifyingLiveModelSnapshotStorage();
		observer = new RecordingBoundedContextObserver();
		snapshots = new SnapshotObservations(observer);
	}

	@Test
	void hitsMissesAndWritesAreObservedByVersionAndReason ( ) {
		Mock domain = domainWithLiveModel();

		domain.event(new FirstDomainEvent("one"));
		domain.event(new FirstDomainEvent("two"));

		// first read: nothing stored, so the load misses -- and 2 replayed events reach the
		// threshold of 2, so a snapshot is written under the current version
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, snapshots.missed(MissReason.ABSENT, "v1"));
		assertEquals(1, snapshots.written("v1"));
		assertEquals(1, snapshots.loads().size());
		assertEquals(1, snapshots.saves().size());

		// second read: the snapshot is found, nothing new to snapshot
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, snapshots.found("v1"));
		assertEquals(1, snapshots.written("v1"));

		// a bumped version misses although a snapshot is stored -- the reason says so
		MeteredLiveModel.VERSION = "v2";
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, snapshots.missed(MissReason.VERSION_MISMATCH, "v2"));
		assertEquals(1, snapshots.found("v1"), "the hit is untouched by the mismatch");

		assertEquals(List.of(), observer.violations());
	}

	/** The observation names the context and the read model, so two snapshotting live models are told apart. */
	@Test
	void theCallsNameTheContextAndTheReadModelAndNestInTheRead ( ) {
		Mock domain = domainWithLiveModel();

		domain.event(new FirstDomainEvent("one"));
		domain.read(MeteredLiveModel.class, "myModel");

		Recording load = observer.last(Observation.SnapshotLoad.class);
		Observation.SnapshotLoad started = load.observation(Observation.SnapshotLoad.class);
		assertEquals("UnitTestBoundedContext", started.boundedContext());
		assertEquals(Observation.SnapshotOwner.LIVE_MODEL, started.owner());
		assertEquals("MeteredLiveModel", started.component());
		assertSame(observer.last(Observation.LiveModelRead.class), load.parent().orElseThrow());
	}

	/** A read restored from a snapshot reports the snapshot as the base it started after. */
	@Test
	void aReadFromASnapshotReportsItsBase ( ) {
		Mock domain = domainWithLiveModel();

		domain.event(new FirstDomainEvent("one"));
		domain.event(new FirstDomainEvent("two"));
		domain.read(MeteredLiveModel.class, "myModel");
		domain.read(MeteredLiveModel.class, "myModel");

		Outcome.LiveModelProjected projected = observer.last(Observation.LiveModelRead.class).outcome(Outcome.LiveModelProjected.class);
		assertEquals(observer.last(Observation.SnapshotLoad.class).outcome(Outcome.SnapshotFound.class).at(), projected.startedAfter().orElseThrow());
		assertEquals(0, projected.eventsStreamed());
	}

	private Mock domainWithLiveModel ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.observer(observer)
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(MeteredLiveModel.class).snapshots(snapshotStorage).eventCountThreshold(2).readAndWrite();
		return buildBoundedContext(builder);
	}

}

class MeteredLiveModel implements ReadModel<MockDomainEvent>, SnapshotCapable<MeteredLiveModel.Data> {

	public static String VERSION = "v1";

	private final String name;
	private Data data = new Data();

	public MeteredLiveModel ( String name ) {
		this.name = name;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
	}

	@Override
	public void when ( Event<MockDomainEvent> event ) {
		data.counter++;
	}

	@Override
	public String readmodelName ( ) {
		return name;
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

/** Keeps one snapshot per key and knows why a load missed. */
class ClassifyingLiveModelSnapshotStorage implements SnapshotStorage<MeteredLiveModel.Data> {

	private final Map<String, String> versionByKey = new HashMap<>();
	private final Map<String, SnapshotRecord<MeteredLiveModel.Data>> snapshotByKey = new HashMap<>();

	@Override
	public Optional<SnapshotRecord<MeteredLiveModel.Data>> load ( String key, String version ) {
		return version.equals(versionByKey.get(key)) ? Optional.ofNullable(snapshotByKey.get(key)) : Optional.empty();
	}

	@Override
	public void save ( String key, String version, MeteredLiveModel.Data snapshot, EventReference lastEventReference ) {
		versionByKey.put(key, version);
		snapshotByKey.put(key, new SnapshotRecord<>(snapshot.clone(), lastEventReference));
	}

	@Override
	public MissReason classifyMiss ( String key, String version ) {
		return versionByKey.containsKey(key) ? MissReason.VERSION_MISMATCH : MissReason.ABSENT;
	}

}
