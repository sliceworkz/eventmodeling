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
package org.sliceworkz.eventmodeling.module.snapshots;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.SnapshotRecord;
import org.sliceworkz.eventstore.events.EventReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * The one place snapshot storage calls are metered, for aggregates and live models alike — the two
 * paths differ only in the meter name prefix and base tags they were constructed with. Every load
 * and save goes through here, so the meters cannot drift apart between the two call sites.
 * <p>
 * Under {@code <prefix>} ({@code sliceworkz.eventmodeling.aggregate.snapshot} or
 * {@code sliceworkz.eventmodeling.readmodel.live.snapshot}), tagged with the base tags
 * ({@code context} plus {@code aggregate}/{@code readmodel}):
 * <ul>
 * <li>{@code <prefix>.read.count} — loads that returned a snapshot, tagged {@code version}</li>
 * <li>{@code <prefix>.write.count} — snapshots saved, tagged {@code version}</li>
 * <li>{@code <prefix>.miss.count} — loads that returned nothing, tagged {@code version} and
 * {@code reason} ({@code absent} / {@code version_mismatch} / {@code unknown}, from
 * {@link SnapshotStorage#classifyMiss}). The mismatch reason is what makes a bumped snapshot
 * version visible: without it, a full replay on every read is indistinguishable from a key that
 * was never snapshotted</li>
 * <li>{@code <prefix>.load.duration} / {@code <prefix>.save.duration} — what the snapshot storage
 * itself costs, kept apart from the surrounding load/render timers, which also contain the event
 * replay</li>
 * <li>{@code <prefix>.failure.count} — storage calls that threw, tagged {@code operation}
 * ({@code load} / {@code save}). The throw is rethrown unchanged; only the counting is added</li>
 * </ul>
 * The {@code version} tag is bounded by construction — a version is a code-level constant like
 * {@code context}, not data — so the per-version registration needs no cap. The timers carry no
 * {@code version} tag: registered once at construction, their series exist from startup, while the
 * counters can only exist from the first call that knows the version.
 * <p>
 * A throwing {@link SnapshotStorage#classifyMiss} is contained here (the miss counts as
 * {@code unknown}): classification is observability and must never fail the read it observes. A
 * throwing load or save keeps failing the caller exactly as it did before metering existed.
 */
public class SnapshotMeters {

	private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotMeters.class);

	private final MeterRegistry meterRegistry;
	private final String prefix;
	private final Tags baseTags;

	private final Timer loadTimer;
	private final Timer saveTimer;

	private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

	public SnapshotMeters ( MeterRegistry meterRegistry, String prefix, Tags baseTags ) {
		this.meterRegistry = meterRegistry;
		this.prefix = prefix;
		this.baseTags = baseTags;
		this.loadTimer = meterRegistry.timer(prefix + ".load.duration", baseTags);
		this.saveTimer = meterRegistry.timer(prefix + ".save.duration", baseTags);
	}

	/**
	 * Loads through the given storage, timing the call and counting the outcome: a hit on
	 * {@code read.count}, a miss on {@code miss.count} with the reason the storage gives, a throw on
	 * {@code failure.count} before it is rethrown.
	 */
	public <SNAPSHOT_TYPE> Optional<SnapshotRecord<SNAPSHOT_TYPE>> load ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage, String key, String version ) {
		long start = System.nanoTime();
		try {
			Optional<SnapshotRecord<SNAPSHOT_TYPE>> result = snapshotStorage.load(key, version);
			if ( result.isPresent() ) {
				readCounter(version).increment();
			} else {
				missCounter(version, classifyMissQuietly(snapshotStorage, key, version)).increment();
			}
			return result;
		} catch ( RuntimeException e ) {
			failureCounter("load").increment();
			throw e;
		} finally {
			loadTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
		}
	}

	/**
	 * Saves through the given storage, timing the call and counting it on {@code write.count} — or
	 * on {@code failure.count} when it throws, before the throw is rethrown.
	 */
	public <SNAPSHOT_TYPE> void save ( SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage, String key, String version, SNAPSHOT_TYPE snapshot, EventReference lastEventReference ) {
		long start = System.nanoTime();
		try {
			snapshotStorage.save(key, version, snapshot, lastEventReference);
			writeCounter(version).increment();
		} catch ( RuntimeException e ) {
			failureCounter("save").increment();
			throw e;
		} finally {
			saveTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
		}
	}

	private MissReason classifyMissQuietly ( SnapshotStorage<?> snapshotStorage, String key, String version ) {
		try {
			MissReason reason = snapshotStorage.classifyMiss(key, version);
			return reason != null ? reason : MissReason.UNKNOWN;
		} catch ( RuntimeException e ) {
			LOGGER.warn("classifyMiss failed for snapshot key '%s', metering the miss as %s".formatted(key, MissReason.UNKNOWN), e);
			return MissReason.UNKNOWN;
		}
	}

	private Counter readCounter ( String version ) {
		return counters.computeIfAbsent("read|" + tagValue(version), cacheKey ->
			meterRegistry.counter(prefix + ".read.count", baseTags.and("version", tagValue(version))));
	}

	private Counter writeCounter ( String version ) {
		return counters.computeIfAbsent("write|" + tagValue(version), cacheKey ->
			meterRegistry.counter(prefix + ".write.count", baseTags.and("version", tagValue(version))));
	}

	private Counter missCounter ( String version, MissReason reason ) {
		return counters.computeIfAbsent("miss|" + reason + "|" + tagValue(version), cacheKey ->
			meterRegistry.counter(prefix + ".miss.count", baseTags.and(Tags.of("version", tagValue(version), "reason", reason.name().toLowerCase()))));
	}

	private Counter failureCounter ( String operation ) {
		return counters.computeIfAbsent("failure|" + operation, cacheKey ->
			meterRegistry.counter(prefix + ".failure.count", baseTags.and("operation", operation)));
	}

	private static String tagValue ( String version ) {
		return version != null ? version : "";
	}

}
