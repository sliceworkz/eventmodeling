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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.readmodels.CacheStatistics;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;

/**
 * Reports what the {@link CachingReadModel}s of one {@link ReadModelModule} did, as one
 * {@link BoundedContextEvent.CachedReadModelSummarized} per read model per window that had any activity.
 * <p>
 * A hit is a lookup in the read model's heap and is reported nowhere else, so this is how a reader of the
 * monitoring stream — the dashboard — learns the hit rate. The read model keeps cumulative counters
 * ({@link CachingReadModel#statistics()}); the reporter emits the difference since its last report, so the
 * read model knows nothing of windows and nothing is reset under a reader. Windows are aligned to the wall
 * clock, {@code [floor(now / window) * window, + window)}, as the port summaries' are, so instances line up.
 * {@link #flush()} reports the unfinished window, which {@code stop()} and {@code terminate()} do before the
 * context reports itself stopped.
 * <p>
 * The thread exists only between {@link #start()} and {@link #stop()}, and only for a module that has a
 * caching read model at all.
 */
final class CachedReadModelReporter {

	private static final Logger LOGGER = LoggerFactory.getLogger(CachedReadModelReporter.class);

	static final Duration TICK = Duration.ofSeconds(1);

	private final List<Reported> reported;
	private final BoundedContextEventEmitter emitter;
	private final Function<Class<?>, BoundedContextEvent.FeatureSlice> slices;
	private final String boundedContext;
	private final String stream;
	private final Clock clock;

	private Thread thread;
	private volatile boolean running;

	private final class Reported {
		private final CachingReadModel<?, ?, ?> readModel;
		private final Duration window;
		private CacheStatistics last;
		private Instant windowStart;

		private Reported ( CachingReadModel<?, ?, ?> readModel, Instant now ) {
			this.readModel = readModel;
			this.window = readModel.policy().reportEvery();
			this.last = readModel.statistics();
			this.windowStart = windowStart(now, window);
		}
	}

	CachedReadModelReporter ( List<CachingReadModel<?, ?, ?>> readModels, String boundedContext, String stream,
			BoundedContextEventEmitter emitter, Function<Class<?>, BoundedContextEvent.FeatureSlice> slices, Clock clock ) {
		this.boundedContext = boundedContext;
		this.stream = stream;
		this.emitter = emitter;
		this.slices = slices;
		this.clock = clock;
		Instant now = clock.instant();
		this.reported = readModels.stream().map(rm -> new Reported(rm, now)).toList();
	}

	synchronized void start ( ) {
		if ( reported.isEmpty() || thread != null ) {
			return;
		}
		running = true;
		Instant now = clock.instant();
		for ( Reported r : reported ) {
			// what happened while stopped was reported by the stop's flush, or not at all: start a window afresh
			r.last = r.readModel.statistics();
			r.windowStart = windowStart(now, r.window);
		}
		thread = Thread.ofVirtual().name("cached-readmodel-reports-" + boundedContext).start(this::run);
	}

	/** Reports the unfinished windows and ends the thread; {@link #start()} begins again. */
	void stop ( ) {
		Thread ended;
		synchronized ( this ) {
			running = false;
			ended = thread;
			thread = null;
		}
		if ( ended != null ) {
			ended.interrupt();
			try {
				ended.join(TICK.toMillis() * 5);
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}
		flush();
	}

	/** Reports every window that had activity, up to now, the unfinished ones included. */
	synchronized void flush ( ) {
		Instant now = clock.instant();
		for ( Reported r : reported ) {
			report(r, now, true);
		}
	}

	private void run ( ) {
		while ( running ) {
			try {
				Thread.sleep(TICK);
			} catch ( InterruptedException e ) {
				return;
			}
			try {
				synchronized ( this ) {
					Instant now = clock.instant();
					for ( Reported r : reported ) {
						if ( !windowStart(now, r.window).equals(r.windowStart) ) {
							report(r, now, false);
						}
					}
				}
			} catch ( RuntimeException e ) {
				LOGGER.error("could not report the caching read models of bounded context '{}'", boundedContext, e);
			}
		}
	}

	// guarded by this: reports the window r is in and moves r on to the window now is in
	private void report ( Reported r, Instant now, boolean partial ) {
		CacheStatistics current = r.readModel.statistics();
		CacheStatistics before = r.last;
		Instant end = partial ? now : r.windowStart.plus(r.window);
		boolean active = current.hits() != before.hits() || current.misses() != before.misses()
				|| current.sharedLoads() != before.sharedLoads() || current.evictions() != before.evictions()
				|| current.expirations() != before.expirations() || current.failedUpdates() != before.failedUpdates();
		if ( active && emitter.enabled() ) {
			emitter.emit(new BoundedContextEvent.CachedReadModelSummarized(boundedContext, r.readModel.readmodelName(),
					r.windowStart, end,
					current.hits() - before.hits(),
					current.misses() - before.misses(),
					current.sharedLoads() - before.sharedLoads(),
					current.eventsLoaded() - before.eventsLoaded(),
					current.eventsCaughtUp() - before.eventsCaughtUp(),
					current.loadMicros() - before.loadMicros(),
					current.evictions() - before.evictions(),
					current.expirations() - before.expirations(),
					current.failedUpdates() - before.failedUpdates(),
					current.size(), current.maxEntries(), current.halted(),
					slices.apply(r.readModel.getClass()), stream));
		}
		r.last = current;
		r.windowStart = windowStart(now, r.window);
	}

	static Instant windowStart ( Instant at, Duration window ) {
		long windowMs = window.toMillis();
		return Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), windowMs) * windowMs);
	}

}
