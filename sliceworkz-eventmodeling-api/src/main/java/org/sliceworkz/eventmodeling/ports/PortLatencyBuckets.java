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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The fixed latency buckets a {@code PortCallsSummarized} counts its calls in, as inclusive upper bounds in
 * microseconds, plus one overflow bucket. Fixed, and shared by everything that buckets a duration, because
 * that is what makes histograms mergeable: windows of one instance, instances of one deployment, and the
 * per-call durations a dashboard buckets itself all add up bucket by bucket. Percentiles do not add up;
 * buckets do, and a percentile is read off the merged buckets.
 */
public final class PortLatencyBuckets {

	/** Inclusive upper bounds, in microseconds: 100µs to 10s. A duration above the last is in the overflow bucket. */
	public static final List<Long> UPPER_BOUNDS_MICROS = List.of(
			100L, 250L, 500L,
			1_000L, 2_500L, 5_000L,
			10_000L, 25_000L, 50_000L,
			100_000L, 250_000L, 500_000L,
			1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L);

	/** How many buckets a histogram has: one per upper bound, plus the overflow bucket. */
	public static final int COUNT = UPPER_BOUNDS_MICROS.size() + 1;

	private PortLatencyBuckets ( ) { }

	/**
	 * @param micros a duration in microseconds
	 * @return the index of the bucket it falls in, {@code COUNT - 1} being the overflow bucket
	 */
	public static int indexOf ( long micros ) {
		for ( int i = 0; i < UPPER_BOUNDS_MICROS.size(); i++ ) {
			if ( micros <= UPPER_BOUNDS_MICROS.get(i) ) {
				return i;
			}
		}
		return COUNT - 1;
	}

	/**
	 * @return a histogram of {@link #COUNT} zeroes
	 */
	public static List<Long> empty ( ) {
		return Collections.nCopies(COUNT, 0L);
	}

	/**
	 * Adds two histograms bucket by bucket. A {@code null} or short histogram counts as zeroes.
	 *
	 * @return the merged histogram, {@link #COUNT} long
	 */
	public static List<Long> merge ( List<Long> a, List<Long> b ) {
		List<Long> merged = new ArrayList<>(COUNT);
		for ( int i = 0; i < COUNT; i++ ) {
			merged.add(at(a, i) + at(b, i));
		}
		return merged;
	}

	/**
	 * Reads a percentile off a histogram as the upper bound of the bucket it falls in — an upper estimate,
	 * which is the safe direction for a latency.
	 *
	 * @param histogram the counts per bucket
	 * @param percentile between 0 and 100
	 * @return the bucket's upper bound in microseconds, {@code -1} for an empty histogram, and
	 *         {@code Long.MAX_VALUE} when it falls in the overflow bucket
	 */
	public static long percentileMicros ( List<Long> histogram, double percentile ) {
		long total = 0;
		for ( int i = 0; i < COUNT; i++ ) {
			total += at(histogram, i);
		}
		if ( total == 0 ) {
			return -1;
		}
		long rank = (long) Math.ceil(total * (percentile / 100.0));
		long seen = 0;
		for ( int i = 0; i < COUNT; i++ ) {
			seen += at(histogram, i);
			if ( seen >= Math.max(1, rank) ) {
				return i < UPPER_BOUNDS_MICROS.size() ? UPPER_BOUNDS_MICROS.get(i) : Long.MAX_VALUE;
			}
		}
		return Long.MAX_VALUE;
	}

	private static long at ( List<Long> histogram, int i ) {
		if ( histogram == null || i >= histogram.size() || histogram.get(i) == null ) {
			return 0;
		}
		return histogram.get(i);
	}

}
