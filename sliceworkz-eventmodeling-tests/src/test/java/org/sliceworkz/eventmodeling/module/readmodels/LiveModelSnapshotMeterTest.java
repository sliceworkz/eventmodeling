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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The live model snapshot path is metered through the same {@code SnapshotMeters} as the aggregate
 * one, under its own prefix and {@code readmodel} tag: hits and writes by version, misses by
 * reason, and timers for the storage calls themselves — kept apart from
 * {@code readmodel.live.duration}, which also contains the event replay.
 */
public class LiveModelSnapshotMeterTest extends AbstractMockDomainTest {

	private static final String PREFIX = "sliceworkz.eventmodeling.readmodel.live.snapshot";

	private ClassifyingLiveModelSnapshotStorage snapshotStorage;
	private SimpleMeterRegistry registry;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		MeteredLiveModel.VERSION = "v1";
		snapshotStorage = new ClassifyingLiveModelSnapshotStorage();
		registry = new SimpleMeterRegistry();
	}

	@Test
	void hitsMissesAndWritesAreCountedByVersionAndReason ( ) {
		Mock domain = domainWithLiveModel();

		domain.event(new FirstDomainEvent("one"));
		domain.event(new FirstDomainEvent("two"));

		// first read: nothing stored, so the load misses -- and 2 replayed events reach the
		// threshold of 2, so a snapshot is written under the current version
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, count(".miss.count", "reason", "absent", "version", "v1"));
		assertEquals(1, count(".write.count", "version", "v1"));
		assertEquals(1, timerCount(".load.duration"));
		assertEquals(1, timerCount(".save.duration"));

		// second read: the snapshot is found, nothing new to snapshot
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, count(".read.count", "version", "v1"));
		assertEquals(1, count(".write.count", "version", "v1"));

		// a bumped version misses although a snapshot is stored -- the reason says so
		MeteredLiveModel.VERSION = "v2";
		domain.read(MeteredLiveModel.class, "myModel");
		assertEquals(1, count(".miss.count", "reason", "version_mismatch", "version", "v2"));
		assertEquals(1, count(".read.count", "version", "v1"), "the hit count is untouched by the mismatch");
	}

	/** The base tags name the read model, so two snapshotting live models keep separate series. */
	@Test
	void theMetersAreTaggedWithContextAndReadModel ( ) {
		Mock domain = domainWithLiveModel();

		domain.event(new FirstDomainEvent("one"));
		domain.read(MeteredLiveModel.class, "myModel");

		assertEquals(1, count(".miss.count",
				"context", "UnitTestBoundedContext",
				"readmodel", "MeteredLiveModel",
				"reason", "absent",
				"version", "v1"));
	}

	private Mock domainWithLiveModel ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.meterRegistry(registry)
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(MeteredLiveModel.class).snapshots(snapshotStorage).eventCountThreshold(2).readAndWrite();
		return buildBoundedContext(builder);
	}

	private double count ( String suffix, String... tags ) {
		Counter counter = registry.find(PREFIX + suffix).tags(tags).counter();
		return counter != null ? counter.count() : 0;
	}

	private long timerCount ( String suffix ) {
		Timer timer = registry.find(PREFIX + suffix).timer();
		return timer != null ? timer.count() : 0;
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
	public void when ( MockDomainEvent event ) {
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
