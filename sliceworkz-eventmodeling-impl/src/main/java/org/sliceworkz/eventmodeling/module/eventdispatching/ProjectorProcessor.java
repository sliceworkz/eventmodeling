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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.module.threading.Processor;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

/**
 * A thin lifecycle wrapper around {@link Projector} that provides background thread management,
 * leader election modes, and start/stop/terminate lifecycle control.
 * <p>
 * All projection mechanics (bookmarks, batching, BatchAwareProjection callbacks) are delegated
 * to the Projector from the eventstore library.
 */
public class ProjectorProcessor<EVENT_TYPE> implements EventStreamEventuallyConsistentAppendListener, Processor {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProjectorProcessor.class);

	private static final long WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS = 10000;
	private static final long WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS = 30000;

	private final Projector<EVENT_TYPE> projector;
	private final ProcessorIdentification processorIdentification;
	private final ProcessorMode originalProcessorMode;
	private final RunListener runListener;

	// opens once this processor has caught up with the stream for the first time after start(), so
	// callers can block until the projection it feeds is usable (see awaitInitialProjection)
	private final CountDownLatch initialProjectionDone = new CountDownLatch(1);

	private volatile ProcessorMode processorMode;
	private volatile ProcessorInstanceMode instanceMode = ProcessorInstanceMode.LEADER;
	private volatile boolean potentiallyNewEventsAppended;

	public ProjectorProcessor (
			ProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance ) {
		this(processorIdentification, eventSource, projection, processorMode, instance, null);
	}

	public ProjectorProcessor (
			ProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance,
			RunListener runListener ) {

		this.processorIdentification = processorIdentification;
		this.originalProcessorMode = processorMode;
		this.processorMode = ProcessorMode.STOPPED;
		this.runListener = runListener;

		// Clean up stale bookmarks for ephemeral storage before building the projector
		if ( processorIdentification.storage() == Storage.EPHEMERAL ) {
			LOGGER.info("EPHEMERAL storage, removing stale bookmark for '{}' ...", processorIdentification);
			eventSource.removeBookmark(processorIdentification.toString());
		}

		this.projector = Projector.from(eventSource)
				.towards(projection)
				.bookmarkProgress()
					.withReader(processorIdentification.toString())
					.withTags(processorIdentification.toTags(instance))
					.readBeforeFirstExecution()
					.done()
				.build();

		eventSource.subscribe(this);
	}

	public ProcessorIdentification identification ( ) {
		return processorIdentification;
	}

	/**
	 * Blocks until this processor has completed its first full catch-up with the stream after
	 * {@link #start()}, i.e. until the projection it feeds reflects everything that was in the
	 * stream at start time.
	 * <p>
	 * The latch is also released when the projector stops on an error, so a waiter does not sit out
	 * its whole timeout on a catch-up that is never going to finish. Waiting on a processor that
	 * only runs on the elected leader is not supported: on a follower the latch stays closed until
	 * the waiter times out.
	 *
	 * @return {@code true} if the initial catch-up completed, {@code false} on timeout
	 */
	public boolean awaitInitialProjection ( long timeout, TimeUnit unit ) throws InterruptedException {
		return initialProjectionDone.await(timeout, unit);
	}

	/**
	 * Whether the initial catch-up is behind us, without blocking. Lets a waiter report which
	 * processors are still outstanding.
	 */
	public boolean initialProjectionDone ( ) {
		return initialProjectionDone.getCount() == 0;
	}

	@Override
	public void terminate ( ) {
		this.instanceMode = ProcessorInstanceMode.TERMINATING;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public void stop ( ) {
		this.processorMode = ProcessorMode.STOPPED;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public void start ( ) {
		this.processorMode = originalProcessorMode;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public EventReference eventsAppended ( EventReference atLeastUntil ) {
		LOGGER.debug("projector processor notified of updates until at least {}", atLeastUntil);
		EventReference lastRef = projector.accumulatedMetrics().lastEventReference();
		if ( lastRef == null || atLeastUntil.happenedAfter(lastRef) ) {
			LOGGER.debug("might be new interesting events, querying them immediately!");
			synchronized ( this ) {
				potentiallyNewEventsAppended = true;
				this.notify();
			}
			return atLeastUntil;
		} else {
			LOGGER.debug("nothing new to process based on this update, already at {}", lastRef);
			return lastRef;
		}
	}

	@Override
	public void run ( ) {
		Thread.currentThread().setName(processorIdentification.id());
		LOGGER.info("projector processor '{}' running ...", processorIdentification);

		while ( instanceMode != ProcessorInstanceMode.TERMINATING ) {
			try {

				if ( processorMode != ProcessorMode.STOPPED ) {

					if ( processorMode == ProcessorMode.RUNNING_ON_ALL_INSTANCES || instanceMode == ProcessorInstanceMode.LEADER ) {

						try {
							// the first run after start() rebuilds/catches up the projection from its bookmark
							// (from scratch for ephemeral storage) and is what start() may be waiting for, so
							// it is reported at info level, unlike the incremental runs that follow
							boolean initialRun = !initialProjectionDone();
							if ( initialRun ) {
								LOGGER.info("'{}' starting initial catch-up ...", processorIdentification);
							}

							long runStartMs = System.currentTimeMillis();
							ProjectorMetrics metrics = projector.run();
							long runDurationMs = System.currentTimeMillis() - runStartMs;

							if ( initialRun ) {
								LOGGER.info("'{}' initial catch-up completed in {} ms: {} events handled, {} streamed in {} queries, last reference {}",
										processorIdentification, runDurationMs, metrics.eventsHandled(), metrics.eventsStreamed(), metrics.queriesDone(), metrics.lastEventReference());
							}

							LOGGER.debug("projector run completed: {} events streamed, {} handled, last reference {}",
									metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());

							if ( runListener != null ) {
								try {
									runListener.onRun(metrics, runDurationMs);
								} catch ( Throwable t ) {
									LOGGER.warn("projector run listener failed: {}", t.getMessage(), t);
								}
							}

							initialProjectionDone.countDown(); // caught up at least once, projection is usable

							// Caught up with the stream — wait for new events or timeout
							synchronized ( this ) {
								if ( ! potentiallyNewEventsAppended ) {
									LOGGER.debug("no new events pending, waiting for {} seconds", (WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS / 1000));
									this.wait(WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS);
									LOGGER.debug("done waiting, or notified that new events could be present");
								}
								potentiallyNewEventsAppended = false;
							}

						} catch ( ProjectorException e ) {
							LOGGER.error("problem in projector for event {} : {} : {}",
									e.getEventReference(),
									e.getCause().getClass(),
									e.getCause().getMessage(),
									e.getCause());
							LOGGER.warn("Stopping projector due to error: {}", e.getCause().getMessage(), e.getCause());
							processorMode = ProcessorMode.STOPPED;
							initialProjectionDone.countDown(); // no catch-up will happen anymore, release anyone waiting for it

						}
					} else {
						LOGGER.debug("we're not leader, not running on this instance");
					}

				} else {
					LOGGER.debug("not running, waiting for further instructions, checking back in {} seconds", (WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS / 1000));
					try {
						synchronized ( this ) {
							this.wait(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
						}
						LOGGER.debug("done waiting or notified, checking new instructions");
					} catch (InterruptedException e) {
						LOGGER.debug("interrupted while waiting in stopped state");
					}
				}

			} catch ( Throwable t ) {
				LOGGER.error("unexpected throwable during processor run: " + t.getMessage() , t);
			}

		}
		LOGGER.info("{} gracefully terminated", processorIdentification);
	}


	/**
	 * Notified after each {@link Projector#run()} cycle with the metrics of that cycle (events
	 * streamed, handled, queries done, last reference) and the wall-clock duration in milliseconds.
	 * <p>
	 * A run corresponds to a full catch-up with the stream — possibly spanning several query batches,
	 * such as the complete rebuild of an ephemeral read model on processor start — so the supplied
	 * metrics aggregate all queries performed during that catch-up.
	 */
	@FunctionalInterface
	public interface RunListener {
		void onRun ( ProjectorMetrics metrics, long durationMs );
	}

	public enum ProcessorMode {
		STOPPED, 					// processing will not run at all
		RUNNING_ON_SINGLE_LEADER, 	// processing will run, on instance selected be via leader election
		RUNNING_ON_ALL_INSTANCES,	 // processing will run, on each instance
	}

	public enum ProcessorInstanceMode {
		LEADER,   // this instance is in charge
		STANDBY,  // another instance is handling everything, but this one could be elected later on at any moment
		TERMINATING, // gracefully end at end of current handling
	}

}
