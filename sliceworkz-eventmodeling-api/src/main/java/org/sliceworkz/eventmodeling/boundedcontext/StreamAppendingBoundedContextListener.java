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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * A {@link BoundedContextListener} that appends every {@link BoundedContextEvent} to a supplied
 * event stream, keeping the kernel's events in a dedicated (e.g. "observability" or monitoring) stream
 * of the store, where a dashboard or any other reader of the store finds them.
 * <p>
 * The supplied stream must be configured for {@link BoundedContextEvent} (and its serialization),
 * for example:
 * <pre>
 *   EventStream&lt;BoundedContextEvent&gt; observability = eventStore.getEventStream(
 *       EventStreamId.forContext("my-context").withPurpose("observability"), BoundedContextEvent.class);
 *   ...
 *   .listener(new StreamAppendingBoundedContextListener(observability))
 * </pre>
 *
 * <h2>The heartbeat</h2>
 * Next to the events, the listener keeps a {@linkplain InstanceHeartbeat heartbeat} for every bounded
 * context it reports on: from its {@code BoundedContextStarting} until its {@code BoundedContextStopping}
 * it places a read position at the head of the stream every interval ({@link InstanceHeartbeat#DEFAULT_INTERVAL}
 * unless the constructor says otherwise), and terminating the context removes it - a {@code stop()},
 * which leaves the process up, keeps it beating. It is what lets a reader of the
 * stream tell a context that is idle from one whose process was killed and so never said it stopped, at
 * the cost of one bookmark row per deployed copy of a context rather than an event per beat.
 * <ul>
 *   <li>One listener may serve several bounded contexts; each gets a heartbeat of its own, and one
 *       virtual thread beats for all of them. The thread exists only while some context is beating, and
 *       parks on a {@link Condition} between beats, so an idle listener holds no platform thread.</li>
 *   <li>A beat that fails (the monitoring store is down) is logged - the first at WARN, the rest at DEBUG
 *       until one succeeds - and never reaches the context: the heartbeat is reporting, like the events.</li>
 *   <li>A monitoring store that is closed ends every heartbeat: closing a storage is terminal.</li>
 *   <li>{@link Duration#ZERO} turns the heartbeat off, for a stream a reader judges by its events alone.</li>
 * </ul>
 */
public class StreamAppendingBoundedContextListener implements BoundedContextListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(StreamAppendingBoundedContextListener.class);

	private final EventStream<BoundedContextEvent> stream;
	private final Duration heartbeatInterval;

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition changed = lock.newCondition();
	/** The contexts beating now, guarded by {@link #lock}. */
	private final Set<Beating> beating = new LinkedHashSet<>();
	/** The thread placing the beats, while there is anything to beat for; guarded by {@link #lock}. */
	private Thread beater;
	/** Whether the previous beat failed, so a monitoring store that stays down logs one warning. */
	private boolean failing;

	public StreamAppendingBoundedContextListener ( EventStream<BoundedContextEvent> stream ) {
		this(stream, InstanceHeartbeat.DEFAULT_INTERVAL);
	}

	/**
	 * @param heartbeatInterval how often each bounded context's heartbeat is placed; {@link Duration#ZERO}
	 *        places none
	 */
	public StreamAppendingBoundedContextListener ( EventStream<BoundedContextEvent> stream, Duration heartbeatInterval ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required");
		}
		if ( heartbeatInterval == null || heartbeatInterval.isNegative() ) {
			throw new IllegalArgumentException("heartbeatInterval must be zero (no heartbeat) or positive");
		}
		this.stream = stream;
		this.heartbeatInterval = heartbeatInterval;
	}

	@Override
	public void on ( EphemeralEvent<BoundedContextEvent> event ) {
		stream.append(AppendCriteria.none(), event);
		if ( heartbeatInterval.isZero() ) {
			return;
		}
		switch ( event.data() ) {
			case BoundedContextEvent.BoundedContextStarting x -> startBeating(new Beating(x.boundedContext(), x.logical(), x.physical(), x.process()));
			// a stop that can be undone keeps beating: the process is up, which is all a heartbeat says
			case BoundedContextEvent.BoundedContextStopping x when !x.restartable() -> stopBeating(new Beating(x.boundedContext(), x.logical(), x.physical(), x.process()));
			default -> { }
		}
	}

	/** Beats once at once, so the context is alive from its first announcement, and then on the timer. */
	private void startBeating ( Beating context ) {
		lock.lock();
		try {
			// a process restarting a context it stopped beats under the same reader: replace, not add
			beating.removeIf(other -> other.reader().equals(context.reader()));
			beating.add(context);
			beat(context);
			if ( beater == null ) {
				beater = Thread.ofVirtual().name("instance-heartbeat").start(this::beatLoop);
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Removes the context's heartbeat. Under the same lock a beat is placed under, so a beat in flight
	 * cannot put the row back after the stop removed it.
	 */
	private void stopBeating ( Beating context ) {
		lock.lock();
		try {
			if ( beating.remove(context) ) {
				try {
					stream.removeBookmark(context.reader());
				} catch ( RuntimeException e ) {
					LOGGER.debug("could not remove the heartbeat of {}: {}", context.reader(), e.toString());
				}
			}
			changed.signalAll();
		} finally {
			lock.unlock();
		}
	}

	private void beatLoop ( ) {
		lock.lock();
		try {
			while ( !beating.isEmpty() ) {
				long nanos = heartbeatInterval.toNanos();
				// a stop signals; the wait then resumes for what is left, so the pace stays the interval
				while ( nanos > 0 && !beating.isEmpty() ) {
					nanos = changed.awaitNanos(nanos);
				}
				for ( Beating context : List.copyOf(beating) ) {
					beat(context);
				}
			}
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		} finally {
			beater = null;
			lock.unlock();
		}
	}

	/** Places one beat; called holding {@link #lock}. */
	private void beat ( Beating context ) {
		try {
			Optional<EventReference> head = stream.head();
			if ( head.isPresent() ) {
				stream.placeReadPosition(context.reader(), head.get(),
						InstanceHeartbeat.tags(context.boundedContext(), context.logical(), context.physical(), context.process(), heartbeatInterval));
			}
			if ( failing ) {
				LOGGER.info("heartbeats are being placed again");
				failing = false;
			}
		} catch ( EventStorageClosedException e ) {
			// closing a storage is terminal: nothing placed here will ever be read again, so the beats end
			// rather than failing every interval for as long as the process lives
			LOGGER.debug("the monitoring store is closed; heartbeats end: {}", e.toString());
			beating.clear();
			changed.signalAll();
		} catch ( RuntimeException e ) {
			if ( failing ) {
				LOGGER.debug("heartbeat of {} could not be placed: {}", context.reader(), e.toString());
			} else {
				LOGGER.warn("heartbeat of {} could not be placed; a reader may take the instance for gone until one is: {}",
						context.reader(), e.toString());
				failing = true;
			}
		}
	}

	/** One deployed copy of a bounded context, as run by one process. */
	private record Beating ( String boundedContext, String logical, String physical, String process ) {

		private String reader ( ) {
			return InstanceHeartbeat.reader(boundedContext, logical, physical);
		}
	}

	/** For tests: whether a thread is beating, which it is exactly while some context is. */
	boolean beating ( ) {
		lock.lock();
		try {
			return beater != null;
		} finally {
			lock.unlock();
		}
	}
}
