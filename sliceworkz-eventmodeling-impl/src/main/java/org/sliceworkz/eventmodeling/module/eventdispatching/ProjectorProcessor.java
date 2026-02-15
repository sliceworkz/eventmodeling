/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification.Storage;
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
	private final EventuallyConsistentProcessorIdentification processorIdentification;
	private final ProcessorMode originalProcessorMode;

	private volatile ProcessorMode processorMode;
	private volatile ProcessorInstanceMode instanceMode = ProcessorInstanceMode.LEADER;
	private volatile boolean potentiallyNewEventsAppended;

	public ProjectorProcessor (
			EventuallyConsistentProcessorIdentification processorIdentification,
			EventSource<EVENT_TYPE> eventSource,
			Projection<EVENT_TYPE> projection,
			ProcessorMode processorMode,
			Instance instance ) {

		this.processorIdentification = processorIdentification;
		this.originalProcessorMode = processorMode;
		this.processorMode = ProcessorMode.STOPPED;

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
							ProjectorMetrics metrics = projector.run();

							LOGGER.debug("projector run completed: {} events streamed, {} handled, last reference {}",
									metrics.eventsStreamed(), metrics.eventsHandled(), metrics.lastEventReference());

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
