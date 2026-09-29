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
package org.sliceworkz.eventmodeling.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The fixed buckets every port latency histogram counts in, which is what lets histograms be added up.
 */
class PortLatencyBucketsTest {

	@Test
	void anUpperBoundIsInclusive ( ) {
		assertEquals(0, PortLatencyBuckets.indexOf(0));
		assertEquals(0, PortLatencyBuckets.indexOf(100));
		assertEquals(1, PortLatencyBuckets.indexOf(101));
		assertEquals(3, PortLatencyBuckets.indexOf(1_000));
		assertEquals(PortLatencyBuckets.COUNT - 2, PortLatencyBuckets.indexOf(10_000_000));
	}

	@Test
	void anythingAboveTheLastBoundIsInTheOverflowBucket ( ) {
		assertEquals(PortLatencyBuckets.COUNT - 1, PortLatencyBuckets.indexOf(10_000_001));
		assertEquals(PortLatencyBuckets.COUNT - 1, PortLatencyBuckets.indexOf(Long.MAX_VALUE));
		assertEquals(PortLatencyBuckets.UPPER_BOUNDS_MICROS.size() + 1, PortLatencyBuckets.COUNT);
	}

	@Test
	void histogramsAddUpBucketByBucket ( ) {
		List<Long> a = counts(0, 3);
		List<Long> b = counts(0, 2);
		b.set(5, 7L);
		List<Long> merged = PortLatencyBuckets.merge(a, b);
		assertEquals(PortLatencyBuckets.COUNT, merged.size());
		assertEquals(5L, merged.get(0));
		assertEquals(7L, merged.get(5));
		assertEquals(merged, PortLatencyBuckets.merge(merged, null), "a missing histogram counts as zeroes");
		assertEquals(merged, PortLatencyBuckets.merge(merged, List.of()), "so does one of an older, shorter shape");
	}

	@Test
	void aPercentileIsTheUpperBoundOfTheBucketItFallsIn ( ) {
		List<Long> histogram = counts(0, 90); // 90 calls at or below 100µs
		histogram.set(6, 9L);                  // 9 at or below 10ms
		histogram.set(PortLatencyBuckets.COUNT - 1, 1L); // 1 above 10s
		assertEquals(100, PortLatencyBuckets.percentileMicros(histogram, 50));
		assertEquals(100, PortLatencyBuckets.percentileMicros(histogram, 90));
		assertEquals(10_000, PortLatencyBuckets.percentileMicros(histogram, 95));
		assertEquals(10_000, PortLatencyBuckets.percentileMicros(histogram, 99));
		assertEquals(Long.MAX_VALUE, PortLatencyBuckets.percentileMicros(histogram, 100));
	}

	@Test
	void anEmptyHistogramHasNoPercentile ( ) {
		assertEquals(-1, PortLatencyBuckets.percentileMicros(PortLatencyBuckets.empty(), 95));
		assertEquals(-1, PortLatencyBuckets.percentileMicros(null, 95));
	}

	private static List<Long> counts ( int bucket, long count ) {
		List<Long> histogram = new ArrayList<>(PortLatencyBuckets.empty());
		histogram.set(bucket, count);
		return histogram;
	}

}
