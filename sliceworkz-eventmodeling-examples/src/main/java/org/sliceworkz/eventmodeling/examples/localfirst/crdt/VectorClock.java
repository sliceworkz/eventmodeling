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
package org.sliceworkz.eventmodeling.examples.localfirst.crdt;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A version vector: per replica, how many of its operations are known. Stored inside every operation
 * (see {@link OperationStamp}) to say which operations of every replica the decision was based on.
 *
 * <p>Two clocks are ordered when one covers the other on every replica; otherwise they are concurrent,
 * which is exactly the case where two replicas decided without knowing about each other.
 *
 * <p>A payload record: the canonical constructor is lenient (a {@code null} map is the empty clock) and
 * no derived method carries a {@code get}/{@code is} prefix, so a JSON mapper sees the counters alone.
 */
public record VectorClock ( Map<String, Long> counters ) {

	public VectorClock {
		counters = counters == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(counters));
	}

	public static VectorClock empty ( ) {
		return new VectorClock(Map.of());
	}

	public long counterOf ( ReplicaId replica ) {
		return counters.getOrDefault(replica.value(), 0L);
	}

	/** This clock with one more operation of the given replica: the clock of a new local operation. */
	public VectorClock increment ( ReplicaId replica ) {
		TreeMap<String, Long> next = new TreeMap<>(counters);
		next.merge(replica.value(), 1L, Long::sum);
		return new VectorClock(next);
	}

	/** The least clock covering both: what a replica knows after taking in the other's operations. */
	public VectorClock mergedWith ( VectorClock other ) {
		TreeMap<String, Long> merged = new TreeMap<>(counters);
		other.counters.forEach((replica, counter) -> merged.merge(replica, counter, Math::max));
		return new VectorClock(merged);
	}

	/** Whether this clock knows at least everything the other knows. */
	public boolean covers ( VectorClock other ) {
		return other.counters.entrySet().stream().allMatch(e -> counters.getOrDefault(e.getKey(), 0L) >= e.getValue());
	}

	/** Whether this clock is strictly in the causal past of the other. */
	public boolean happenedBefore ( VectorClock other ) {
		return other.covers(this) && !equals(other);
	}

	/** Neither in the past of the other: two decisions taken without knowledge of each other. */
	public boolean concurrentWith ( VectorClock other ) {
		return !covers(other) && !other.covers(this);
	}

	@Override
	public String toString ( ) {
		return counters.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(", ", "[", "]"));
	}

}
