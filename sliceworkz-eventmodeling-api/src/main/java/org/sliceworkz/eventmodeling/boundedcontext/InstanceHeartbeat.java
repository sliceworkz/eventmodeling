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
package org.sliceworkz.eventmodeling.boundedcontext;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The wire format of the heartbeat a {@link StreamAppendingBoundedContextListener} keeps for every bounded
 * context it reports on, and the one place a reader of the monitoring store turns it back into a value.
 *
 * <h2>Why a bookmark, and not an event</h2>
 * A process that is killed (SIGKILL, an OOM kill, a lost machine) never reports that it stopped: its last
 * word on the monitoring stream is whatever it was doing, and a context that was idle reads as running for
 * as long as the stream is kept. Telling the two apart needs a sign of life that keeps coming while there
 * is nothing else to say. An event per beat would do it at the cost of an ever-growing stream - over a
 * million events a year per instance at one beat every 30 seconds, kept for nothing. A bookmark is one row
 * per reader name that every placement overwrites, and its {@link Bookmark#updatedAt()} is refreshed by
 * every placement, the same reference or not: the heartbeat costs one row per deployed copy of a context,
 * whatever its age.
 *
 * <h2>What a beat is</h2>
 * A read position ({@link org.sliceworkz.eventstore.stream.EventSource#placeReadPosition}) at the head of
 * the monitoring stream, under the reader {@link #reader(String, String, String)}, carrying the instance's
 * identity and the beat interval as {@linkplain #of(Bookmark) tags}. A read position rather than a handled
 * reference because nothing was handled: placed at the head, it reads as a reader that is fully caught up,
 * so a screen counting bookmark lag never shows a heartbeat as a reader falling behind.
 * <ul>
 *   <li><b>Keyed on the deployed copy, not on the process.</b> The reader names the bounded context and
 *       its logical and physical instance; the process is a tag. A restart under the same name takes the
 *       row over rather than adding one, so the rows are bounded by the instance names a deployment ever
 *       used.</li>
 *   <li><b>Placed from {@code BoundedContextStarting} until {@code BoundedContextStopping}</b>, the
 *       starting phase included - a context projecting a long history into memory is alive. It says the
 *       <em>process</em> is alive, not what the context is doing: a context taken out of service with
 *       {@code stop()} keeps its process up, listening for the instruction to start again, and keeps
 *       beating. Terminating it - which a JVM shutdown hook does on an ordinary stop of the process -
 *       removes the row, since the stop events already say what became of it; a row left behind with an
 *       ageing {@code updatedAt} is exactly a context that went away without saying so.</li>
 *   <li><b>Judged by the reader</b>: a context whose newest beat is more than
 *       {@link #MISSED_BEATS_BEFORE_UNRESPONSIVE} intervals old is presumed gone. The timestamp is on the
 *       storage's clock (the database server's on PostgreSQL), so a reader on another machine allows for
 *       skew - see {@link Beat#age(Instant)}.</li>
 * </ul>
 */
public final class InstanceHeartbeat {

	/** The prefix of every heartbeat's reader name, which keeps them apart from the readers of processors. */
	public static final String READER_PREFIX = "x-instance-heartbeat:";

	/** The tag naming the bounded context a heartbeat is for. */
	public static final String TAG_BOUNDED_CONTEXT = "x-heartbeat-bounded-context";

	/** The tag carrying the beat interval in milliseconds, so a reader knows how late is late. */
	public static final String TAG_INTERVAL_MS = "x-heartbeat-interval-ms";

	/** The tags naming the instance, the same keys every kernel event carries them under. */
	public static final String TAG_LOGICAL = "x-instance-logical";
	public static final String TAG_PHYSICAL = "x-instance-physical";
	public static final String TAG_PROCESS = "x-instance-process";

	/** How often a context beats unless its listener says otherwise. */
	public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

	/**
	 * How many beats a reader lets go by before it presumes the context gone. More than one, because a beat
	 * is placed on a timer next to a database that may be briefly slow, and a GC pause of a few seconds is
	 * not a dead process.
	 */
	public static final int MISSED_BEATS_BEFORE_UNRESPONSIVE = 3;

	private InstanceHeartbeat ( ) { }

	/** The reader name a deployed copy of a bounded context beats under. */
	public static String reader ( String boundedContext, String logical, String physical ) {
		return READER_PREFIX + boundedContext + "/" + logical + "/" + physical;
	}

	/** Whether a bookmark is a heartbeat rather than a processor's position. */
	public static boolean isHeartbeat ( String reader ) {
		return reader != null && reader.startsWith(READER_PREFIX);
	}

	/** The tags a beat carries; a value that is null or blank is left out rather than stored. */
	static Tags tags ( String boundedContext, String logical, String physical, String process, Duration interval ) {
		Tags tags = Tags.none();
		tags = with(tags, TAG_BOUNDED_CONTEXT, boundedContext);
		tags = with(tags, TAG_LOGICAL, logical);
		tags = with(tags, TAG_PHYSICAL, physical);
		tags = with(tags, TAG_PROCESS, process);
		return with(tags, TAG_INTERVAL_MS, Long.toString(interval.toMillis()));
	}

	private static Tags with ( Tags tags, String key, String value ) {
		if ( value == null || value.isBlank() ) {
			return tags;
		}
		return tags.merge(Tags.of(Tag.of(key, value.strip())));
	}

	/**
	 * The heartbeat a bookmark records, or empty for a bookmark that is not one (or one too damaged to say
	 * which context it is for).
	 */
	public static Optional<Beat> of ( Bookmark bookmark ) {
		if ( bookmark == null || !isHeartbeat(bookmark.reader()) ) {
			return Optional.empty();
		}
		Tags tags = bookmark.tags();
		String boundedContext = value(tags, TAG_BOUNDED_CONTEXT);
		if ( boundedContext == null ) {
			return Optional.empty();
		}
		Duration interval = DEFAULT_INTERVAL;
		String intervalMs = value(tags, TAG_INTERVAL_MS);
		if ( intervalMs != null ) {
			try {
				long ms = Long.parseLong(intervalMs);
				if ( ms > 0 ) {
					interval = Duration.ofMillis(ms);
				}
			} catch ( NumberFormatException e ) {
				// a value this version cannot read: judge it by the default interval
			}
		}
		return Optional.of(new Beat(boundedContext, value(tags, TAG_LOGICAL), value(tags, TAG_PHYSICAL),
				value(tags, TAG_PROCESS), interval, bookmark.updatedAt()));
	}

	private static String value ( Tags tags, String key ) {
		return tags.tags(key).stream().map(Tag::value).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
	}

	/**
	 * One heartbeat as read back: which context on which instance, which process placed it last, how often
	 * it beats and when it last did.
	 *
	 * @param at when the beat was placed, on the storage's clock
	 */
	public record Beat ( String boundedContext, String logical, String physical, String process, Duration interval, Instant at ) {

		/** How long a beat may be overdue before the context is presumed gone. */
		public Duration tolerance ( ) {
			return interval.multipliedBy(MISSED_BEATS_BEFORE_UNRESPONSIVE);
		}

		/**
		 * How long ago the beat was placed, judged against {@code now}. A beat stamped later than
		 * {@code now} - the storage's clock running ahead of the reader's - is no age at all rather than a
		 * negative one.
		 */
		public Duration age ( Instant now ) {
			Duration age = Duration.between(at, now);
			return age.isNegative() ? Duration.ZERO : age;
		}

		/** Whether the context has missed so many beats that it is presumed gone. */
		public boolean overdue ( Instant now ) {
			return age(now).compareTo(tolerance()) > 0;
		}
	}
}
