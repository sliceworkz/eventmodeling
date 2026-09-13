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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.module.threading.Processor;
import org.sliceworkz.eventmodeling.module.threading.ProcessorInstanceMode;
import org.sliceworkz.eventmodeling.module.threading.ProcessorMode;
import org.sliceworkz.eventmodeling.readmodels.SelfBookmarkingProjection;
import org.sliceworkz.eventmodeling.readmodels.StaleLeadershipException;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
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

	// The retry pacing for a projection failure that is not known to be permanent. The values mirror
	// Automation.DEFAULT_POLL_INTERVAL and DEFAULT_MAX_BACKOFF deliberately -- same failure class, same
	// pacing -- but are not imported from there: this class serves read models, translators and
	// dispatchers alike and has no business depending on the automation package.
	static final String RETRY_INITIAL_PROPERTY = "sliceworkz.eventmodeling.projector.retry.initial.ms";
	static final String RETRY_MAX_PROPERTY = "sliceworkz.eventmodeling.projector.retry.max.ms";
	static final long DEFAULT_RETRY_INITIAL_MS = 10000;
	static final long DEFAULT_RETRY_MAX_MS = 300000; // 5 minutes

	/**
	 * After this many consecutive failed runs a leader-only processor reports
	 * {@link #shouldYieldLeadership()}, so the elector hands its lease to an instance whose
	 * dependencies may be healthy — this instance's target database being down says nothing about the
	 * standby's connectivity.
	 */
	static final int YIELD_LEADERSHIP_AFTER_FAILED_RUNS = 3;

	private final ProcessorIdentification processorIdentification;
	private final ProcessorMode originalProcessorMode;
	private final ProjectorListener projectorListener;

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
	// set when this processor is stopped on its own account -- retiring itself on a permanent projection
	// failure, or stopped by an operator -- as opposed to being stopped by its lifecycle; what the leader
	// elector reads to release the lease of a processor that will not work it (see
	// Processor.stoppedItself). Cleared by start() and stop(): either is an explicit instruction that
	// supersedes it
	private volatile boolean stoppedItself;

	// how many runs in a row have now failed without the failure being permanent; what the backoff and
	// the leadership yield are keyed on. Reset by a run that completes, and by a demotion -- a standby
	// retries nothing, and a re-won lease deserves fresh attempts
	private volatile int consecutiveFailedRuns;
	// the cause of the most recent projection failure, permanent or not; stays available after recovery
	private volatile Throwable lastFailure;
	// the cause of the permanent failure that retired this processor, null while it is running. Kept
	// apart from lastFailure, as on AutomationStatus: a running processor has usually survived
	// failures, and the one an operator wants is the one that stopped it
	private volatile Throwable stoppedBy;

	// read at construction rather than into a static, so a test can shorten the pacing per context
	private final long retryInitialMs = Long.getLong(RETRY_INITIAL_PROPERTY, DEFAULT_RETRY_INITIAL_MS);
	private final long retryMaxMs = Long.getLong(RETRY_MAX_PROPERTY, DEFAULT_RETRY_MAX_MS);

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
			ProjectorListener projectorListener ) {
		this(processorIdentification, eventSource, projection, processorMode, instance, projectorListener, null);
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
			ProjectorListener projectorListener,
			SelfBookmarkingProjection ownBookmark ) {

		this.processorIdentification = processorIdentification;
		this.originalProcessorMode = processorMode;
		this.processorMode = ProcessorMode.STOPPED;
		this.projectorListener = projectorListener;
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
		this.consecutiveFailedRuns = 0; // an explicit start deserves fresh attempts, whatever came before
		this.processorMode = originalProcessorMode;
		synchronized ( this ) {
			this.notify();
		}
		notifyListener("started", ProjectorListener::onStarted);
	}

	@Override
	public boolean stoppedItself ( ) {
		return stoppedItself;
	}

	@Override
	public boolean shouldYieldLeadership ( ) {
		return consecutiveFailedRuns >= YIELD_LEADERSHIP_AFTER_FAILED_RUNS;
	}

	/**
	 * Restarts this processor if it has stopped, so it resumes projecting from where its position
	 * durably left off — the retired projector's cursor was rolled back to the start of the batch it
	 * failed on, so nothing is skipped by the restart either.
	 *
	 * @return {@code true} if it was stopped and has been restarted, {@code false} if it was running
	 */
	public boolean restart ( ) {
		if ( processorMode != ProcessorMode.STOPPED ) {
			LOGGER.debug("'{}' is already running, nothing to restart", processorIdentification);
			return false;
		}
		LOGGER.info("restarting '{}'", processorIdentification);
		stoppedBy = null; // it is running again; what stopped it stays available as lastFailure
		start();
		return true;
	}

	/**
	 * Stops this processor on an operator's say-so, so it projects nothing further until something
	 * restarts it — see {@code ProcessorAdminCapability.stopProcessor}. Flagged as stopped on its own
	 * account rather than by the lifecycle, exactly like a permanent failure: that is what makes the
	 * leader elector release the lease of a leader-only processor, so another instance takes over. The
	 * projector's cursor is untouched — a run in progress completes its batch, or rolls it back whole,
	 * before the loop parks — so a restart resumes where the position durably left off.
	 *
	 * @return {@code true} if it was running and is now stopped, {@code false} if it was already stopped
	 */
	public boolean stopByOperator ( ) {
		if ( processorMode == ProcessorMode.STOPPED ) {
			LOGGER.debug("'{}' is already stopped, nothing to stop", processorIdentification);
			return false;
		}
		LOGGER.info("stopping '{}' on an operator's instruction - it will not project again until it is restarted", processorIdentification);
		stoppedItself = true;
		processorMode = ProcessorMode.STOPPED;
		initialProjectionDone.countDown(); // no catch-up will happen while stopped, release anyone waiting for it
		synchronized ( this ) {
			this.notify();
		}
		notifyListener("stopped", ProjectorListener::onStoppedByOperator);
		return true;
	}

	/**
	 * What this processor is doing, for an operator. A snapshot read without synchronisation, exactly
	 * as an automation's status is — the module reporting it adds the name and kind, since this class
	 * projects read models, translators and dispatchers alike and has no business knowing which.
	 */
	public ProcessorSnapshot snapshot ( ) {
		return new ProcessorSnapshot(
				processorMode != ProcessorMode.STOPPED,
				instanceMode == ProcessorInstanceMode.LEADER,
				consecutiveFailedRuns,
				lastFailure,
				stoppedBy);
	}

	/** The operator-facing state of this processor, in the terms {@code ProcessorStatus} reports. */
	public record ProcessorSnapshot (
			boolean running,
			boolean leader,
			int consecutiveFailedRuns,
			Throwable lastFailure,
			Throwable stoppedBy ) { }

	@Override
	public void instanceMode ( ProcessorInstanceMode mode, long fencingToken ) {
		if ( mode == ProcessorInstanceMode.LEADER && ownBookmark != null ) {
			// recorded before the mode flips, so the first batch of this leadership already writes
			// under the token -- and the reseed the promotion triggers is what raises the stored
			// token before the resume position is read, fencing the previous leader out
			ownBookmark.fencedBy(fencingToken);
		}
		instanceMode(mode);
	}

	@Override
	public void instanceMode ( ProcessorInstanceMode mode ) {
		ProcessorInstanceMode previous = this.instanceMode;
		this.instanceMode = mode;
		if ( mode == ProcessorInstanceMode.LEADER && previous == ProcessorInstanceMode.STANDBY ) {
			// resume from the shared position, not from this instance's stale in-memory cursor
			reseedProjector = true;
		}
		if ( mode == ProcessorInstanceMode.STANDBY ) {
			// a demotion ends the failure streak: a standby retries nothing, and a re-won lease
			// deserves fresh attempts -- which also bounds the yield ping-pong between two failing
			// instances to roughly one hand-over per lease ttl
			consecutiveFailedRuns = 0;
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
								LOGGER.info("'{}' promoted to leader, re-seeding projector from its durable position ...", processorIdentification);
								this.projector = createProjector();
								// cleared only once the rebuild succeeded: createProjector reads the durable
								// resume position and can throw (the same dead database a failing projection
								// writes into), and clearing first would leave the retry running the stale
								// cursor the reseed exists to replace
								reseedProjector = false;
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

							if ( consecutiveFailedRuns > 0 ) {
								LOGGER.info("'{}' recovered after {} failed run(s)", processorIdentification, consecutiveFailedRuns);
								consecutiveFailedRuns = 0;
							}

							if ( initialRun ) {
								LOGGER.info("'{}' initial catch-up completed in {} ms: {} events handled, {} streamed in {} queries, last reference {}",
										processorIdentification, runDurationMs, metrics.eventsHandled(), metrics.eventsStreamed(), metrics.queriesDone(), metrics.lastEventReference());
							}

							LOGGER.debug("projector run completed: {} events streamed, {} handled, last reference {}",
									metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());

							notifyListener("run", listener -> listener.onRun(metrics, runDurationMs));

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
							Throwable cause = e.getCause();
							lastFailure = cause;

							if ( isPermanentFailure(cause) ) {
								LOGGER.error("permanent problem in projector of '{}' for event {} : {} : {} -- stopping, retrying could not help",
										processorIdentification,
										e.getEventReference(),
										cause.getClass(),
										cause.getMessage(),
										cause);
								stoppedBy = cause;
								// self-imposed, not lifecycle: flagged before the mode flip so the leader
								// elector never sees a self-stopped processor it would renew the lease for
								stoppedItself = true;
								processorMode = ProcessorMode.STOPPED;
								initialProjectionDone.countDown(); // no catch-up will happen anymore, release anyone waiting for it
								// after the mode is set, so a listener that goes looking finds a processor that
								// really has retired rather than one about to
								notifyListener("stopped", listener -> listener.onStopped(e));

							} else {
								// Anything else is retried: the Projector rolled its cursor back to the start
								// of the failed batch, so the next run re-offers exactly those events and no
								// event is ever skipped. The realistic transient cause -- the database this
								// projection writes into being down -- and an outright bug in the projection
								// are indistinguishable from here, and of the two ways to be wrong, retrying
								// a bug is a visible stall (a climbing consecutiveFailedRuns, one Failed
								// report per round) where stopping on an outage is a read model that never
								// comes back without a restart.
								consecutiveFailedRuns++;
								int failedRuns = consecutiveFailedRuns;
								long delayMs = retryDelayMs(failedRuns);
								LOGGER.error("problem in projector of '{}' for event {} : {} : {} -- retrying in {} ms ({} consecutive failed run(s))",
										processorIdentification,
										e.getEventReference(),
										cause.getClass(),
										cause.getMessage(),
										delayMs,
										failedRuns,
										cause);
								// a waiter on the initial projection is released rather than held for the
								// length of an outage; the projection keeps catching up in the background
								// once the cause clears, exactly like one that overran the startup timeout
								initialProjectionDone.countDown();
								notifyListener("failed", listener -> listener.onFailed(e, failedRuns));
								backOff(delayMs);
							}
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
				// Reaching here is a failure of the loop machinery itself, outside the projector run --
				// realistically a promotion's reseed hitting the same dead database the projection
				// writes into (resumeFrom() throws raw, not as a ProjectorException). Looping straight
				// round used to retry that at full thread speed; pace it like a poll instead.
				try {
					synchronized ( this ) {
						if ( !terminating ) {
							this.wait(WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS);
						}
					}
				} catch ( InterruptedException interrupted ) {
					LOGGER.debug("interrupted while waiting after an unexpected throwable");
				}
			}

		}
		LOGGER.info("{} gracefully terminated", processorIdentification);
	}


	/**
	 * Whether a projection failure is one that retrying cannot help, so the processor retires instead
	 * of backing off.
	 * <p>
	 * The classification is over the cause the {@code ProjectorException} wraps — the only signal a
	 * caller of {@code Projector.run()} has — and it is deliberately a closed list: everything not on
	 * it is retried, because the realistic transient failure (the database a projection writes into
	 * being down) arrives as whatever the projection threw and cannot be told apart from a bug.
	 * <ul>
	 * <li>{@code EventDeserializationException} — a poison event: the stored payload and the stream's
	 *     type mappings do not change between attempts, on this instance or any other</li>
	 * <li>{@code EventSerializationException} — the same, for a payload that cannot be written</li>
	 * <li>{@code EventStorageClosedException} — closing is terminal, a lifecycle bug in the calling
	 *     code. Checked as its own case because it <em>extends</em> {@code EventStorageException},
	 *     the possibly-transient kind that is exactly worth retrying</li>
	 * <li>{@code StaleLeadershipException} — this leadership was fenced out by a newer leader; the
	 *     stored token only grows, so retrying here fights the fence forever. Retiring is what hands
	 *     the lease back (the elector reads {@code stoppedItself})</li>
	 * </ul>
	 */
	static boolean isPermanentFailure ( Throwable cause ) {
		return cause instanceof EventDeserializationException
				|| cause instanceof EventSerializationException
				|| cause instanceof EventStorageClosedException
				|| cause instanceof StaleLeadershipException;
	}

	/**
	 * How long to back off before retry number {@code consecutiveFailedRuns}: the initial delay,
	 * doubling per further failed run, capped — the same shape as the automation default
	 * ({@code Automation.delayBeforeNextBatch}), so a dependency that is down for an hour costs a
	 * handful of attempts instead of a steady hammering.
	 */
	private long retryDelayMs ( int consecutiveFailedRuns ) {
		long delay = retryInitialMs << Math.min(consecutiveFailedRuns - 1, 16);
		return Math.min(delay, retryMaxMs);
	}

	/**
	 * Waits out {@code timeoutMs} whatever else happens on this monitor, cut short only by shutdown, a
	 * stop, or a demotion — a standby has nothing to back off from, and should be parked in the standby
	 * branch instead.
	 * <p>
	 * This cannot be the plain caught-up wait: append notifications arrive as a bare {@code notify()}
	 * on this monitor, so a parked thread is woken by any of them however it came to be parked, and a
	 * failing projection released by every append would retry at the pace of the traffic feeding its
	 * stream instead of the backoff — a busy system hammering the very database that is down. The loop
	 * to the deadline is what makes the backoff hold. (The same reasoning as the automation's
	 * {@code backOff}.)
	 */
	private void backOff ( long timeoutMs ) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		try {
			synchronized ( this ) {
				while ( !terminating && processorMode != ProcessorMode.STOPPED && instanceMode == ProcessorInstanceMode.LEADER ) {
					long remaining = deadline - System.currentTimeMillis();
					if ( remaining <= 0 ) {
						return;
					}
					this.wait(remaining);
				}
			}
		} catch ( InterruptedException e ) {
			// deliberately not restoring the flag: this loop parks again on its next pass, and a set
			// flag would make that throw immediately and spin. An interrupt here comes from the thread
			// manager giving up on a terminate() that has already set the flag, so the loop ends anyway
			LOGGER.debug("interrupted while backing off");
		}
	}

	/**
	 * Delivers one notification to the listener, if there is one, absorbing whatever it throws.
	 * <p>
	 * Observation must not cost projection: {@code onRun} is called from inside the projector loop, so
	 * a throw there used to be answered by the loop's catch-all — which cannot tell a broken listener
	 * from a broken projection and abandons the round either way. {@code onStopped} is worse still: it
	 * runs on the path that is already handling a failure, and a throw would replace the failure being
	 * reported with the reporting of it.
	 */
	private void notifyListener ( String what, Consumer<ProjectorListener> notification ) {
		if ( projectorListener == null ) {
			return;
		}
		try {
			notification.accept(projectorListener);
		} catch ( Throwable t ) {
			LOGGER.warn("projector listener failed on '{}' for '{}': {}", what, processorIdentification, t.getMessage(), t);
		}
	}

	/**
	 * What this processor tells its module about its own progress, so the module can report it in the
	 * terms its components are named in — this class projects read models, translators and dispatchers
	 * alike and has no business knowing which.
	 * <p>
	 * Every method is a no-op by default: a module supplies the ones it reports and ignores the rest.
	 * A listener that throws is contained by this processor and never costs a projection, but that is
	 * the last line rather than the intended one — an implementation doing I/O contains its own
	 * failures.
	 */
	public interface ProjectorListener {

		/**
		 * Called from {@link ProjectorProcessor#start()}, on the caller's thread, before this
		 * processor's loop has necessarily seen it. For a leader-only processor this says the processor
		 * exists and is willing, not that it is the one projecting — that is
		 * {@link ProcessorInstanceMode} and the leader elector reports it separately.
		 */
		default void onStarted ( ) { }

		/**
		 * Called after each {@link Projector#run()} cycle with the metrics of that cycle (events
		 * streamed, handled, queries done, last reference) and the wall-clock duration in milliseconds.
		 * <p>
		 * A run corresponds to a full catch-up with the stream — possibly spanning several query
		 * batches, such as the complete rebuild of an ephemeral read model on processor start — so the
		 * supplied metrics aggregate all queries performed during that catch-up.
		 */
		default void onRun ( ProjectorMetrics metrics, long durationMs ) { }

		/**
		 * Called once per projection run that failed with something worth retrying, before the
		 * processor backs off and tries again — so the rate is bounded by the backoff, not by the
		 * failure. The counterpart of {@link #onStopped}, which now means retired for good.
		 *
		 * @param failure the {@code ProjectorException} of this run; its cause is the signal
		 * @param consecutiveFailedRuns how many runs in a row have now failed, 1 for the first
		 */
		default void onFailed ( ProjectorException failure, int consecutiveFailedRuns ) { }

		/**
		 * Called when a <em>permanent</em> projection failure — one retrying could not help: a poison
		 * event, a closed storage, a fenced-out leadership — has retired this processor. Nothing
		 * retries after it, so this is the last thing the processor says until something starts it
		 * again; a failure worth retrying is reported through {@link #onFailed} instead, per round.
		 */
		default void onStopped ( ProjectorException failure ) { }

		/**
		 * Called when an operator has stopped this processor through
		 * {@link ProjectorProcessor#stopByOperator()}, on the caller's thread. Nothing failed; the
		 * processor projects nothing further until something starts it again.
		 */
		default void onStoppedByOperator ( ) { }

	}

}
