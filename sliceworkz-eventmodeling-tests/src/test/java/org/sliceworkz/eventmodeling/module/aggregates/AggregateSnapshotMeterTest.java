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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The snapshot storage calls of an aggregate are metered: hits and writes tagged by version, misses
 * tagged by the reason the storage gives, timers for what the storage itself costs, and failures
 * counted before the throw goes on. The miss reason is the load-bearing part — a bumped snapshot
 * version means a full replay on every load, and without the {@code version_mismatch} reason that
 * is indistinguishable from a key that was never snapshotted.
 */
public class AggregateSnapshotMeterTest extends AbstractMockDomainTest {

	private static final String PREFIX = "sliceworkz.eventmodeling.aggregate.snapshot";

	private ClassifyingSnapshotStorage snapshotStorage;
	private SimpleMeterRegistry registry;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		MeteredAggregate.VERSION = "v1";
		snapshotStorage = new ClassifyingSnapshotStorage();
		registry = new SimpleMeterRegistry();
	}

	/** A load that finds nothing, a save, a load that hits, and a load after a version bump each land on their own meter. */
	@Test
	void hitsMissesAndWritesAreCountedByVersionAndReason ( ) {
		Mock domain = domainWithAggregate();

		// nothing stored yet: the load misses, and the storage says why
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, count(".miss.count", "reason", "absent", "version", "v1"));
		assertEquals(1, timerCount(".load.duration"));
		assertEquals(0, timerCount(".save.duration"));

		// two events reach the threshold of 2: one snapshot is written, under the current version
		aggregate.doSomething();
		aggregate.doSomething();
		assertEquals(1, count(".write.count", "version", "v1"));
		assertEquals(1, timerCount(".save.duration"));

		// reload: the snapshot is found
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, count(".read.count", "version", "v1"));

		// a bumped version misses although a snapshot is stored -- the reason says so
		MeteredAggregate.VERSION = "v2";
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		assertEquals(1, count(".miss.count", "reason", "version_mismatch", "version", "v2"));
		assertEquals(1, count(".read.count", "version", "v1"), "the hit count is untouched by the mismatch");
	}

	/** A storage that does not override classifyMiss still has its misses counted, without a reason. */
	@Test
	void aStorageWithoutMissClassificationCountsMissesAsUnknown ( ) {
		snapshotStorage.classifies = false;

		Mock domain = domainWithAggregate();
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		assertEquals(1, count(".miss.count", "reason", "unknown", "version", "v1"));
	}

	/** A classification that throws never fails the read: the miss is counted as unknown and the load goes on. */
	@Test
	void aThrowingClassificationIsContainedAndCountedAsUnknown ( ) {
		snapshotStorage.failClassification = true;

		Mock domain = domainWithAggregate();
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		assertEquals(0, aggregate.getCounter(), "the aggregate loads normally");
		assertEquals(1, count(".miss.count", "reason", "unknown", "version", "v1"));
	}

	/** A load or save that throws is counted by operation, and the throw still reaches the caller. */
	@Test
	void storageFailuresAreCountedByOperation ( ) {
		Mock domain = domainWithAggregate();

		snapshotStorage.failLoads = true;
		assertThrows(IllegalStateException.class, () -> domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123")));
		assertEquals(1, count(".failure.count", "operation", "load"));
		assertEquals(1, timerCount(".load.duration"), "the failing load is still timed");

		snapshotStorage.failLoads = false;
		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		snapshotStorage.failSaves = true;
		aggregate.doSomething();
		assertThrows(IllegalStateException.class, () -> aggregate.doSomething()); // second event reaches the threshold, the save throws
		assertEquals(1, count(".failure.count", "operation", "save"));
		assertEquals(0, count(".write.count", "version", "v1"), "a failed save is not a write");
	}

	/** Every snapshot meter name carries exactly one set of tag keys — Prometheus rejects anything else. */
	@Test
	void noSnapshotMeterNameCarriesTwoDifferentTagKeySets ( ) {
		Mock domain = domainWithAggregate();

		MeteredAggregate aggregate = domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		aggregate.doSomething();
		aggregate.doSomething();
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));
		MeteredAggregate.VERSION = "v2";
		domain.aggregate(MeteredAggregate.class, Tags.of("businessObject", "123"));

		Map<String, Set<Set<String>>> keySetsByName = new HashMap<>();
		registry.getMeters().forEach(meter ->
			keySetsByName.computeIfAbsent(meter.getId().getName(), name -> new HashSet<>()).add(tagKeys(meter)));

		String offenders = keySetsByName.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1)
				.map(entry -> "%s -> %s".formatted(entry.getKey(), entry.getValue()))
				.collect(Collectors.joining(", "));
		assertTrue(offenders.isEmpty(), "meter names registered under several tag key sets: " + offenders);
	}

	private Mock domainWithAggregate ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.meterRegistry(registry)
				.instance(InstanceFactory.determine("unittests"));
		builder.aggregate(MeteredAggregate.class).snapshots(snapshotStorage).eventCountThreshold(2).readAndWrite();
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

	private static Set<String> tagKeys ( Meter meter ) {
		return meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).collect(Collectors.toSet());
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
	public void when ( MockDomainEvent event ) {
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
