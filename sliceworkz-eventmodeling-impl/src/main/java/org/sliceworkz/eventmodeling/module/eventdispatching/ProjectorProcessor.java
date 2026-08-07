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
import org.sliceworkz.eventmodeling.module.threading.ProcessorInstanceMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.readmodels.SelfBookmarkingProjection;
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

	private final ProcessorIdentification processorIdentification;
	private final ProcessorMode originalProcessorMode;
	private final RunListener runListener;

	// retained so a promotion can rebuild the projector from the current shared position
	private final EventSource<EVENT_TYPE> eventSource;
	private final Projection<EVENT_TYPE> projection;
	private final Instance instance;
	private final SelfBookmarkingProjection ownBookmark;

	// opens once this processor has caught up with the stream for the first time after start(), so
	// callers can block until the projection it feeds is usable (see awaitInitialProjection)
	private final CountDownLatch initialProjectionDone = new CountDownLatch(1);

	// rebuilt on promotion (see reseedProjector); volatile so eventsAppended, on the storage's
	// notification thread, always compares against the projector the loop is actually running
	private volatile Projector<EVENT_TYPE> projector;

	private volatile ProcessorMode processorMode;
	// A leader-only processor starts as a standby and is promoted by the leader elector; a processor
	// running on every instance has no election result to wait for. Termination is deliberately not a
	// value of this enum: it is the separate flag below, so a standby can be shut down as itself.
	private volatile ProcessorInstanceMode instanceMode;
	private volatile boolean terminating;
	// set on promotion: the in-memory cursor of the projector is stale the moment another instance
	// has projected past it, so the first leader run must resume from the shared position, not from
	// wherever this instance's projector happened to stop reading
	private volatile boolean reseedProjector;
	private volatile boolean potentiallyNewEventsAppended;
	// set when this processor retires itself on a projection failure, as opposed to being stopped by
	// its lifecycle; what the leader elector reads to release the lease of a processor that will not
	// work it (see Processor.stoppedItself). Cleared by start() and stop(): either is an explicit
	// instruction that supersedes the self-imposed stop
	private volatile boolean stoppedItself;

	public ProjectorProcessor (
			ProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance ) {
		this(processorIdentification, eventSource, projection, processorMode, instance, null, null);
	}

	public ProjectorProcessor (
			ProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance,
			RunListener runListener ) {
		this(processorIdentification, eventSource, projection, processorMode, instance, runListener, null);
	}

	/**
	 * @param ownBookmark the projection's own record of how far it has come, when it keeps one
	 *                    alongside the state it projects. Where present it decides where this
	 *                    processor resumes, and the bookmark in the event store is not consulted —
	 *                    see {@link SelfBookmarkingProjection}. {@code null} for a projection that
	 *                    has no such record, which is every processor but a durable read model's
	 */
	public ProjectorProcessor (
			ProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance,
			RunListener runListener,
			SelfBookmarkingProjection ownBookmark ) {

		this.processorIdentification = processorIdentification;
		this.originalProcessorMode = processorMode;
		this.processorMode = ProcessorMode.STOPPED;
		this.runListener = runListener;
		this.eventSource = eventSource;
		this.projection = projection;
		this.instance = instance;
		this.ownBookmark = ownBookmark;
		this.instanceMode = processorMode == ProcessorMode.RUNNING_ON_ALL_INSTANCES
				? ProcessorInstanceMode.LEADER
				: ProcessorInstanceMode.STANDBY;

		// Clean up stale bookmarks for ephemeral storage before building the projector
		if ( processorIdentification.storage() == Storage.EPHEMERAL ) {
			LOGGER.info("EPHEMERAL storage, removing stale bookmark for '{}' ...", processorIdentification);
			eventSource.removeBookmark(processorIdentification.toString());
		}

		this.projector = createProjector();

		eventSource.subscribe(this);
	}

	/**
	 * Builds the projector at its resume point, re-reading that point from where it durably lives.
	 * <p>
	 * Called at construction, and again on every promotion from standby to leader: the projector
	 * holds its cursor in memory, so after a spell as standby that cursor describes where <em>this
	 * instance</em> stopped reading, while the previous leader has long projected past it. Rebuilding
	 * re-runs the resume logic — the projection's own transactional position for a
	 * {@link SelfBookmarkingProjection}, the shared event-store bookmark otherwise — so a fresh
	 * leader continues where the deployment got to, not where this JVM did.
	 */
	private Projector<EVENT_TYPE> createProjector ( ) {
		Projector.Builder<EVENT_TYPE> builder = Projector.from(eventSource).towards(projection);

		if ( ownBookmark != null ) {
			// The projection wrote its position and its state in one transaction, so its position is
			// the only one that cannot disagree with what it holds. Resume from that, and read the
			// event store's bookmark not at all -- an absent position means "nothing is projected",
			// and falling back would hand an empty read model a bookmark describing rows it lost.
			EventReference resumeFrom = ownBookmark.resumeFrom().orElse(null);
			LOGGER.info("'{}' keeps its own bookmark, resuming from {}", processorIdentification,
					resumeFrom == null ? "the beginning of the stream" : resumeFrom);
			builder = builder.startingAfter(resumeFrom)
					.bookmarkProgress()
						.withReader(processorIdentification.toString())
						.withTags(processorIdentification.toTags(instance))
						// written, never read: the event store bookmark stays as the record an
						// operator and the dashboard read, and lags the truth by at most one batch
						.readOnManualTriggerOnly()
						.done();
		} else {
			builder = builder.bookmarkProgress()
					.withReader(processorIdentification.toString())
					.withTags(processorIdentification.toTags(instance))
					.readBeforeFirstExecution()
					.done();
		}

		return builder.build();
	}

	public ProcessorIdentification identification ( ) {
		return processorIdentification;
	}

	/** The mode this processor was registered with — what decides whether it is leader-electable. */
	public ProcessorMode configuredMode ( ) {
		return originalProcessorMode;
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
		this.terminating = true;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public void stop ( ) {
		this.stoppedItself = false;
		this.processorMode = ProcessorMode.STOPPED;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public void start ( ) {
		this.stoppedItself = false;
		this.processorMode = originalProcessorMode;
		synchronized ( this ) {
			this.notify();
		}
	}

	@Override
	public boolean stoppedItself ( ) {
		return stoppedItself;
	}

	@Override
	public void instanceMode ( ProcessorInstanceMode mode ) {
		ProcessorInstanceMode previous = this.instanceMode;
		this.instanceMode = mode;
		if ( mode == ProcessorInstanceMode.LEADER && previous == ProcessorInstanceMode.STANDBY ) {
			// resume from the shared position, not from this instance's stale in-memory cursor
			reseedProjector = true;
		}
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

		while ( !terminating ) {
			try {

				if ( processorMode != ProcessorMode.STOPPED ) {

					if ( processorMode == ProcessorMode.RUNNING_ON_ALL_INSTANCES || instanceMode == ProcessorInstanceMode.LEADER ) {

						try {
							if ( reseedProjector ) {
								// promoted since the last pass: rebuild the projector so it resumes from
								// the position the previous leader durably left, not from this instance's
								// in-memory cursor
								reseedProjector = false;
								LOGGER.info("'{}' promoted to leader, re-seeding projector from its durable position ...", processorIdentification);
								this.projector = createProjector();
							}

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
								// The terminate() that set the flag notified us while we were in the run
								// above, with nothing waiting to hear it. Re-check the flag before parking:
								// otherwise that notification is lost, shutdown sits out this whole timeout,
								// and the thread manager's grace period ends in an interrupt instead.
								if ( ! potentiallyNewEventsAppended && !terminating ) {
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
							// self-imposed, not lifecycle: flagged before the mode flip so the leader
							// elector never sees a self-stopped processor it would renew the lease for
							stoppedItself = true;
							processorMode = ProcessorMode.STOPPED;
							initialProjectionDone.countDown(); // no catch-up will happen anymore, release anyone waiting for it

						}
					} else {
						// Standing by: parked, not spinning. Woken instantly by instanceMode(LEADER),
						// stop() or terminate(); the timeout is only a safety net. Re-checks its reasons
						// for parking under the monitor, so a promotion arriving just before the wait is
						// not lost.
						LOGGER.debug("'{}' standing by, not the elected leader on this instance", processorIdentification);
						synchronized ( this ) {
							if ( !terminating && instanceMode == ProcessorInstanceMode.STANDBY && processorMode != ProcessorMode.STOPPED ) {
								this.wait(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
							}
						}
					}

				} else {
					LOGGER.debug("not running, waiting for further instructions, checking back in {} seconds", (WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS / 1000));
					try {
						synchronized ( this ) {
							if ( !terminating ) { // same lost-notify race as above
								this.wait(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
							}
						}
						LOGGER.debug("done waiting or notified, checking new instructions");
					} catch (InterruptedException e) {
						LOGGER.debug("interrupted while waiting in stopped state");
					}
				}

			} catch ( InterruptedException interrupted ) {
				// The abrupt half of shutdown: whatever is still parked when the thread manager's grace
				// period runs out gets interrupted. On that path the loop condition below ends the run --
				// reporting it as an unexpected throwable, stack trace and all, made a clean stop look
				// like a failure.
				if ( terminating ) {
					LOGGER.debug("interrupted while terminating");
					Thread.currentThread().interrupt(); // pass it on, we are on our way out anyway
				} else {
					LOGGER.warn("'{}' interrupted while running", processorIdentification, interrupted);
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

}
