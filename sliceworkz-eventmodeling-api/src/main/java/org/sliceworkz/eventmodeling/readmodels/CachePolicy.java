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
package org.sliceworkz.eventmodeling.readmodels;

import java.time.Duration;
import java.time.InstantSource;

/**
 * How much a {@link CachingReadModel} keeps in memory, and for how long.
 *
 * <p>{@code maxEntries} is the bound that matters: it is what keeps a read model over ten million
 * customers at the size of the few thousand being worked with. When a load would exceed it, the entry
 * read least recently goes. {@code expireAfterAccess}, when set, also lets an entry go once it has not
 * been read for that long, so a quiet period gives the memory back. Neither bounds freshness: an entry
 * is kept current by the read model's projector for as long as it is kept at all.
 *
 * <p>{@code reportEvery} is how often a registered read model tells its bounded context's listener what it
 * did — hits, loads, evictions — as a {@code CachedReadModelSummarized}, a minute unless said otherwise; a
 * quiet window reports nothing. {@code reportsEachRead} adds a {@code CachedReadModelRead} per read, on the
 * reader's thread and in its flow, for a read model read rarely enough that each read is worth a line in the
 * monitoring record (as a live read model's {@code LiveModelProjected} is); the windows are reported all the
 * same, and stay what the counts are taken from. {@code clock} is what the expiry is measured on, the system
 * clock unless a test says otherwise.
 *
 * @param maxEntries        the most entries kept, at least 1
 * @param expireAfterAccess how long an entry is kept without being read, or {@code null} for no expiry
 * @param reportEvery       the length of the windows its activity is reported in, at least a second
 * @param clock             the clock the expiry is measured on
 * @param reportsEachRead   whether every read is also reported on its own
 */
public record CachePolicy ( int maxEntries, Duration expireAfterAccess, Duration reportEvery, InstantSource clock, boolean reportsEachRead ) {

	/** The windows a caching read model's activity is reported in, unless the policy says otherwise. */
	public static final Duration DEFAULT_REPORT_EVERY = Duration.ofMinutes(1);

	public CachePolicy {
		if ( maxEntries < 1 ) {
			throw new IllegalArgumentException("maxEntries must be at least 1, was " + maxEntries);
		}
		if ( expireAfterAccess != null && ( expireAfterAccess.isZero() || expireAfterAccess.isNegative() ) ) {
			throw new IllegalArgumentException("expireAfterAccess must be positive, was " + expireAfterAccess);
		}
		if ( reportEvery == null || reportEvery.compareTo(Duration.ofSeconds(1)) < 0 ) {
			throw new IllegalArgumentException("reportEvery must be at least a second, was " + reportEvery);
		}
		if ( clock == null ) {
			throw new IllegalArgumentException("clock must not be null");
		}
	}

	/** A policy whose reads are reported in windows only. */
	public CachePolicy ( int maxEntries, Duration expireAfterAccess, Duration reportEvery, InstantSource clock ) {
		this(maxEntries, expireAfterAccess, reportEvery, clock, false);
	}

	/** At most {@code maxEntries} entries, least recently read evicted first, and no expiry. */
	public static CachePolicy maxEntries ( int maxEntries ) {
		return new CachePolicy(maxEntries, null, DEFAULT_REPORT_EVERY, InstantSource.system(), false);
	}

	/** This policy, with entries also evicted once they have not been read for {@code idle}. */
	public CachePolicy expireAfterAccess ( Duration idle ) {
		return new CachePolicy(maxEntries, idle, reportEvery, clock, reportsEachRead);
	}

	/** This policy, with its activity reported in windows of {@code window}. */
	public CachePolicy reportEvery ( Duration window ) {
		return new CachePolicy(maxEntries, expireAfterAccess, window, clock, reportsEachRead);
	}

	/**
	 * This policy, with every read also reported on its own, as a {@code CachedReadModelRead}: a write to the
	 * monitoring store per read, so for a read model read a few times a minute rather than a thousand.
	 */
	public CachePolicy reportEachRead ( ) {
		return new CachePolicy(maxEntries, expireAfterAccess, reportEvery, clock, true);
	}

	/** This policy, with expiry measured on {@code clock} — for a test that wants to move time itself. */
	public CachePolicy clock ( InstantSource clock ) {
		return new CachePolicy(maxEntries, expireAfterAccess, reportEvery, clock, reportsEachRead);
	}

}
