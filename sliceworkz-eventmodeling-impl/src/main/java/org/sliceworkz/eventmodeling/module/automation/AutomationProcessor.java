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
package org.sliceworkz.eventmodeling.module.automation;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.Processor;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentBookmarkListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

public class AutomationProcessor<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements EventStreamEventuallyConsistentBookmarkListener, Processor {

	private static final Logger LOGGER = LoggerFactory.getLogger(AutomationProcessor.class);

	private static final long WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS = 10000;
	private static final long WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS = 30000;

	private final Limit batchSize;

	private EventSource<DOMAIN_EVENT_TYPE> eventSource;
	private Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation;
	private ProcessorMode originalProcessorMode;
	private ProcessorMode processorMode;
	private ProcessorIdentification processorIdentification; // this is us
	private ProcessorIdentification monitoredProcessorIdentification; // this is the readmodel-building processor we will shadow

	private Function<Tracing, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationContextFactory;

	private ProcessorInstanceMode instanceMode = ProcessorInstanceMode.LEADER; // TOOD implement leader selection on processors
	private Instance instance;

	private boolean monitoredBookmarkMissingWarned = false;

	/**
	 * Set when the projector filling our todo list moves its bookmark, cleared when we read that todo
	 * list. It is what tells an unproductive batch apart from a pointless one: a batch that produced no
	 * event of its own has nothing for the catch-up guard to hold it against, but if the todo list has
	 * moved underneath it there is new work to see and no reason to sit out the poll interval.
	 */
	private volatile boolean monitoredBookmarkMoved = false;

	/** The most recent throwable out of a handler, and the one that stopped us, for {@link #status()}. */
	private volatile Throwable lastFailure;
	private volatile Throwable stoppedBy;

	/**
	 * Failed items, counted here as well as into the meter. The meter cannot serve
	 * {@link #status()}: the default registry is an empty {@code Metrics.globalRegistry} composite, whose
	 * counters are no-ops that read 0 forever, so an operator's view of what went wrong would depend on
	 * whether anyone happened to wire up monitoring.
	 */
	private final AtomicLong itemsFailed = new AtomicLong();

	/**
	 * Batches in a row that failed and handled nothing. Drives the backoff the automation is asked for,
	 * and is what tells a stalled automation apart from a busy one on {@link #status()}: an automation
	 * with failures behind it is ordinary, one that has not got anywhere in the last N batches is not.
	 */
	private volatile int consecutiveFailedBatches;

	private final String boundedContext;
	private final MeterRegistry meterRegistry;
	private final Counter batchCounter;
	private final Counter itemsHandledCounter;
	private final Counter itemsFailedCounter;
	private final Timer batchTimer;
	private final BoundedContextEventEmitter eventEmitter;

	public AutomationProcessor ( ProcessorIdentification processorIdentification, ProcessorIdentification monitoredProcessorIdentification, EventStream<DOMAIN_EVENT_TYPE> eventSource, Function<Tracing, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationContextFactory, Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation, ProcessorMode processorMode, Instance instance, String boundedContext, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.automationContextFactory = automationContextFactory;
		this.automation = automation;
		this.originalProcessorMode = processorMode;
		this.processorMode = ProcessorMode.STOPPED; // initialize as STOPPED, don't run before start() or things might nog have been initialized in the bounded context impl
		this.processorIdentification = processorIdentification;
		this.monitoredProcessorIdentification = monitoredProcessorIdentification;
		this.eventSource = eventSource;
		this.instance = instance;
		this.boundedContext = boundedContext;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;

		int declaredBatchSize = automation.batchSize();
		if ( declaredBatchSize <= 0 ) {
			throw new IllegalArgumentException("batch size %d of automation '%s' is invalid, should be larger than 0".formatted(declaredBatchSize, automation.getClass().getSimpleName()));
		}
		this.batchSize = Limit.to(declaredBatchSize);

		// Initialize metrics with base tags
		Tags baseTags = Tags.of("context", boundedContext)
				.and("automation", processorIdentification.id());

		this.batchCounter = meterRegistry.counter("sliceworkz.eventmodeling.automation.batch", baseTags);
		this.itemsHandledCounter = meterRegistry.counter("sliceworkz.eventmodeling.automation.items.handled", baseTags);
		this.itemsFailedCounter = meterRegistry.counter("sliceworkz.eventmodeling.automation.items.failed", baseTags);
		this.batchTimer = meterRegistry.timer("sliceworkz.eventmodeling.automation.batch.duration", baseTags);

		eventSource.subscribe(this);
	}

	@Override
	public void terminate ( ) {
		this.instanceMode = ProcessorInstanceMode.TERMINATING;
		synchronized ( this ) { // escape the wait state if needed
			this.notify();
		}
	}
	
	@Override
	public void stop ( ) {
		this.processorMode = ProcessorMode.STOPPED;
		synchronized ( this ) { // escape the wait state if needed
			this.notify();
		}
	}

	@Override
	public void start ( ) {
		start(BoundedContextEvent.AutomationStartReason.BOUNDED_CONTEXT_START);
	}

	private void start ( BoundedContextEvent.AutomationStartReason reason ) {
		this.processorMode = originalProcessorMode;
		synchronized ( this ) { // escape the wait state if needed
			this.notify();
		}
		eventEmitter.emit(new BoundedContextEvent.AutomationStarted(boundedContext, processorIdentification.id(), reason, eventEmitter.sliceFor(automation.getClass())));
	}

	@Override
	public void bookmarkUpdated (String reader, EventReference processedUntil ) {
		ProcessorIdentification processor = ProcessorIdentification.parse(reader);

		if ( processor.equals(monitoredProcessorIdentification)) {
			LOGGER.debug("monitored event processor {} moved bookmark to  {}", processor.toString(), processedUntil);

			// always of interest to us, as we'll probably be running behind now. Remembered rather than
			// only signalled: a move that lands while we are handling a batch has nothing waiting to hear
			// it, and parking afterwards for the full timeout would sit out a todo list that has already
			// changed. Cleared when we next read the todo list, so it only ever means "changed since then"
			monitoredBookmarkMoved = true;
			synchronized ( this ) {
				this.notify();
			}
		} else {
			LOGGER.debug("event processor {} moved bookmark to  {}, not of our concern", processor.toString(), processedUntil);
		}
	}
	
	@Override
	public void run ( ) {
		Thread.currentThread().setName(processorIdentification.id()); // make the thread easily recognizable
		LOGGER.info("automation event processor '{}' running ...", processorIdentification.toString());

		while ( instanceMode != ProcessorInstanceMode.TERMINATING ) {
			
			try {
	
				Optional<EventReference> lastReference = Optional.empty();
				boolean mustFetchBookmark = true;
				
				// if instance is running ...
				if ( processorMode != ProcessorMode.STOPPED ) {
					
					if ( processorMode == ProcessorMode.RUNNING_ON_ALL_INSTANCES || instanceMode == ProcessorInstanceMode.LEADER ) {
	
						if ( mustFetchBookmark ) {
							lastReference = eventSource.getBookmark(processorIdentification.toString()); // get last produced event from bookmark of previous run
							if ( lastReference != null && lastReference.isPresent() ) {
								LOGGER.debug("last produced event was {}", lastReference.get());
							} else {
								LOGGER.debug("no run done yet starting from start of stream");
							}
							mustFetchBookmark = false; // as long as this thread is processing the next round, no need to go and fetch the bookmark again from storage
						}
						
						// cleared before the read, so that a move arriving from here on is one this round has
						// not seen and is a reason to come straight back rather than park
						monitoredBookmarkMoved = false;
						Optional<EventReference> monitoredBookmark = eventSource.getBookmark(monitoredProcessorIdentification.toString()); // get position up until which the readmodel has been updated
						
						if ( monitoredBookmark.isPresent() && hasCaughtUp(monitoredBookmark.get(), lastReference) ) {
							LOGGER.debug("monitoredBookmark is at {}, our own bookmark is at {}, processing can continue", monitoredBookmark.get(), lastReference.orElse(null));
							monitoredBookmarkMissingWarned = false; // monitored projector is alive — re-arm the warning for any future disappearance
							
							try {
								if ( monitoredBookmark.isPresent() ) {
									LOGGER.debug("monitored bookmark is at {}", monitoredBookmark.get());

									Tracing tracing = Tracing.init(instance).channel(processorIdentification.type()).actor(processorIdentification.id());

									LOGGER.debug("starting processing of max {} items at a time", batchSize);

									// Create automation context with tracing for proper correlation
									AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context = automationContextFactory.apply(tracing);

									// Time the batch processing and count items
									Timer.Sample sample = Timer.start(meterRegistry);
									long batchStartMs = System.currentTimeMillis();
									BatchOutcome outcome = handleBatch(context);
									sample.stop(batchTimer);

									// Record metrics
									batchCounter.increment();
									itemsHandledCounter.increment(outcome.handled);
									itemsFailedCounter.increment(outcome.failed);
									itemsFailed.addAndGet(outcome.failed);

									if ( outcome.streamed > 0 && eventEmitter.enabled() ) {
										long duration = System.currentTimeMillis() - batchStartMs;
										BoundedContextEvent.Metrics metrics = new BoundedContextEvent.Metrics(duration, 0, outcome.streamed, outcome.handled, outcome.lastProducedEvent);
										eventEmitter.emit(new BoundedContextEvent.AutomationProcessed(boundedContext, processorIdentification.id(), metrics, eventEmitter.sliceFor(automation.getClass())), tracing);
									}

									if ( outcome.lastProducedEvent != null ) {
										// set our position to the last event we produced, we won't do a new run until the readmodel has been updated
										eventSource.placeBookmark(processorIdentification.toString(), outcome.lastProducedEvent, processorIdentification.toTags(instance));
									}

									// a batch that handled something is progress, whatever else went wrong in it, and so
									// is one where nothing failed - only a batch that failed and got nowhere backs off
									boolean batchGotNowhere = outcome.handled == 0 && outcome.failed > 0;
									consecutiveFailedBatches = batchGotNowhere ? consecutiveFailedBatches + 1 : 0;

									if ( outcome.stopAutomation ) {
										LOGGER.warn("stopping automation '{}' as its failure handling asked for it - it will not run again until it is restarted", processorIdentification);
										stoppedBy = outcome.lastFailure;
										processorMode = ProcessorMode.STOPPED;
										eventEmitter.emit(new BoundedContextEvent.AutomationStopped(boundedContext, processorIdentification.id(), failureOf(outcome.lastFailure), eventEmitter.sliceFor(automation.getClass())), tracing);
									} else if ( outcome.streamed >= batchSize.value() && outcome.lastProducedEvent != null ) {
										// A full window and a bookmark that moved: there is plausibly more behind it, and the
										// guard at the top of the loop now has something to hold us against. Without a moved
										// bookmark that guard cannot engage, so going straight round again would re-read the
										// same window at full speed for as long as it stays unchanged.
										LOGGER.debug("not at end of list - doing new batch of {}", batchSize);
									} else {
										long delayMs = delayBeforeNextBatch(consecutiveFailedBatches, outcome.lastFailure);
										if ( batchGotNowhere ) {
											// Held here even if the todo list has moved: a change to the list says nothing
											// about whether whatever this automation failed on has recovered, and releasing
											// on it would tie the retry rate to the traffic feeding the list - so a busy
											// system would hammer a dependency that is down rather than back off from it.
											LOGGER.debug("batch failed without handling anything ({} in a row), waiting {} ms before trying again", consecutiveFailedBatches, delayMs);
											backOff(delayMs);
										} else {
											LOGGER.debug("no new todo items to handle, waiting up to {} ms", delayMs);
											waitForNewWork(delayMs);
										}
										LOGGER.debug("done waiting, or notified that readmodel was updated and new items could be present");
									}


								} else {
									LOGGER.debug("monitored bookmark is not set yet, no readmodel to process");

								}

							} catch (Throwable t ) {
								// Reaching here is a failure of the batch machinery itself: everything a todo item can
								// throw is contained per item in handleBatch. Reading the todo list or bookmarking is
								// what is left, and both are worth another round rather than retiring the automation.
								Throwable r = determineRootCause(t);
								LOGGER.error("problem running batch of automation '{}': rootcause {} : {}", processorIdentification, r.getClass(), r.getMessage(), r );
								waitForWork(WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS);
							}
						} else {
							// risk of handling item twice ...
							if ( monitoredBookmark.isEmpty() ) {
								// No bookmark at all for the read model we follow. Two likely causes:
								//  - The monitored projector has never persisted a position yet (cold start, no
								//    events to process).
								//  - Our monitoredProcessorIdentification doesn't match the id the projector
								//    actually writes (e.g. a storage-class mismatch between SHARED/EPHEMERAL/LOCAL).
								// We warn once so the second case is loud at boot; the flag is reset as soon as
								// a bookmark appears so a genuine "no events yet" stays quiet.
								if ( !monitoredBookmarkMissingWarned ) {
									LOGGER.warn("no bookmark found for monitored read-model processor '{}' — if the read model has events to project, check that its registered processor id matches (e.g. storage class shared vs ephemeral vs local)", monitoredProcessorIdentification);
									monitoredBookmarkMissingWarned = true;
								} else {
									LOGGER.debug("monitoredBookmark still absent for {}, waiting", monitoredProcessorIdentification);
								}
							} else {
								LOGGER.debug("monitoredBookmark is {}, our own bookmark is {}, processing cannot continue until readmodel has kept up", monitoredBookmark, lastReference);
							}
							waitForNewWork(WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS);
							LOGGER.debug("done waiting, or notified that readmodel was updated and new items could be present");
						}
	
					} else {
						LOGGER.debug("we're not leader, not running on this instance");
					}
					
				} else {
					mustFetchBookmark = true;
					LOGGER.debug("not running, waiting for further instructions, checking back in {} seconds", (WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS/1000));
					// TODO maybe synchronize on other object than to allow notify() upon state change from STOPPED to RUNNING again, independently of notifies for new events in stream?
					waitForWork(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
					LOGGER.debug("done waiting or notified, checking new instructions");
				}
			} catch ( Throwable t ) {
				LOGGER.error("unexpected throwable during processor run: " + determineRootCause(t), t);
			}
			
		}
		LOGGER.info("{} gracefully terminated", processorIdentification.toString());
	}
	
	/**
	 * Asks the automation how long to wait, contained: a delay is a policy an implementation supplies, and
	 * one that throws or returns nonsense must not take the processor with it or turn into a busy loop.
	 */
	private long delayBeforeNextBatch ( int consecutiveFailedBatches, Throwable lastFailure ) {
		try {
			Duration delay = automation.delayBeforeNextBatch(consecutiveFailedBatches, lastFailure);
			if ( delay == null || delay.isNegative() ) {
				LOGGER.warn("automation '{}' asked for a delay of {}, using {} instead", processorIdentification, delay, Automation.DEFAULT_POLL_INTERVAL);
				return Automation.DEFAULT_POLL_INTERVAL.toMillis();
			}
			return delay.toMillis();
		} catch ( Throwable t ) {
			LOGGER.error("automation '{}' threw while being asked for its delay, using {}", processorIdentification, Automation.DEFAULT_POLL_INTERVAL, t);
			return Automation.DEFAULT_POLL_INTERVAL.toMillis();
		}
	}

	/**
	 * Whether the read model we shadow has been projected up to and including the last event we produced,
	 * which is what makes it safe to take a fresh look at the todo list.
	 * <p>
	 * The comparison is over the total {@code (tx, position, index)} order the event store defines, not
	 * over positions. The two are genuinely different orders: on Postgres a position is a {@code bigserial}
	 * and a transaction id an {@code xid8}, assigned independently, so an event can carry a lower position
	 * and a higher transaction than one that committed before it. Comparing positions alone reports the
	 * projector as caught up while it is not, and the todo list is then re-read while it still holds items
	 * this automation has already handled — a duplicate that nothing else in this loop would catch.
	 *
	 * @param monitoredBookmark where the read model's projector has got to
	 * @param ourBookmark the last event we produced, empty when we have not produced one yet
	 */
	static boolean hasCaughtUp ( EventReference monitoredBookmark, Optional<EventReference> ourBookmark ) {
		return ourBookmark.isEmpty() || !ourBookmark.get().happenedAfter(monitoredBookmark);
	}

	/**
	 * Handles one batch of todo items, containing whatever a single item throws.
	 * <p>
	 * Items are pulled from the todo list one at a time, and the next one is only taken once the current
	 * one has been handled. That is deliberate rather than incidental: handling an item may raise events
	 * that cancel or supersede the items behind it, and a todo list is allowed to anticipate its own
	 * projection to withhold them (see {@code TodoListReadModel.streamItems}).
	 */
	private BatchOutcome handleBatch ( AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context ) {
		BatchOutcome outcome = new BatchOutcome();

		try ( Stream<TODO_ITEM_TYPE> items = automation.getTodoList().streamItems(batchSize) ) {
			Iterator<TODO_ITEM_TYPE> iterator = items.iterator();
			while ( iterator.hasNext() ) {
				if ( instanceMode == ProcessorInstanceMode.TERMINATING || processorMode == ProcessorMode.STOPPED ) {
					LOGGER.debug("abandoning the rest of the batch, this processor is stopping");
					return outcome;
				}
				TODO_ITEM_TYPE item = iterator.next();
				outcome.streamed++;
				if ( !handleItem(item, context, outcome) ) {
					return outcome; // the batch was abandoned, either for this round or for good
				}
			}
		}
		return outcome;
	}

	/**
	 * Handles a single todo item, applying the automation's own failure policy to anything it throws.
	 *
	 * @return {@code true} if the batch should continue with the next item
	 */
	private boolean handleItem ( TODO_ITEM_TYPE item, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context, BatchOutcome outcome ) {
		try {
			Optional<EventReference> produced = automation.handle(item, context);
			outcome.handled++;
			if ( produced != null && produced.isPresent() ) {
				outcome.lastProducedEvent = produced.get();
			}
			return true;
		} catch ( Throwable t ) {
			outcome.failed++;
			outcome.lastFailure = t;
			lastFailure = t;
			AutomationFailureAction action = determineFailureAction(item, t, context);
			Throwable r = determineRootCause(t);

			// An item is never handled twice within one batch: a failure worth retrying in milliseconds is
			// about whatever the handler called rather than about the item, and belongs inside handle().
			// What is useful here is backing the whole batch off, which is what happens between batches --
			// immediately when the todo list has moved in the meantime, after the poll interval when it has
			// not, so a conflict with another writer comes straight back and a dead dependency does not spin
			switch ( action ) {
				case CONTINUE_AND_RETRY_ITEM_LATER -> {
					LOGGER.error("automation '{}' failed on a todo item, leaving it for a later batch and carrying on: rootcause {} : {}", processorIdentification, r.getClass(), r.getMessage(), t);
					return true;
				}
				case RETRY_ITEM -> {
					LOGGER.error("automation '{}' failed on a todo item, abandoning the rest of this batch so it is retried first: rootcause {} : {}", processorIdentification, r.getClass(), r.getMessage(), t);
					return false;
				}
				case STOP_AUTOMATION -> {
					LOGGER.error("automation '{}' failed on a todo item: rootcause {} : {}", processorIdentification, r.getClass(), r.getMessage(), t);
					outcome.stopAutomation = true;
					return false;
				}
			}
			return false;
		}
	}

	private AutomationFailureAction determineFailureAction ( TODO_ITEM_TYPE item, Throwable cause, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context ) {
		try {
			AutomationFailureAction action = automation.onFailure(item, cause, context);
			return action != null ? action : AutomationFailureAction.RETRY_ITEM;
		} catch ( Throwable t ) {
			LOGGER.error("failure handler of automation '{}' threw, abandoning the rest of this batch", processorIdentification, t);
			return AutomationFailureAction.RETRY_ITEM;
		}
	}

	/**
	 * Waits for the todo list to be worth reading again, for at most {@code timeoutMs}.
	 * <p>
	 * Returns immediately when the projector filling that list has moved its bookmark since this round
	 * read it. That is not an optimisation: the notification is a bare {@code notify()}, so one arriving
	 * while a batch was running is lost, and parking on it afterwards means sitting out the full poll
	 * interval with changed work already waiting. It matters most exactly where the catch-up guard cannot
	 * help — a batch whose appends were all de-duplicated by their idempotency key, or whose handler
	 * raised nothing, bookmarks nothing and would otherwise crawl through a backlog one poll at a time.
	 * <p>
	 * Note what this does <em>not</em> do: a bookmark that has not moved still parks, so a batch that can
	 * see no new work does not spin.
	 */
	private void waitForNewWork ( long timeoutMs ) {
		if ( monitoredBookmarkMoved ) {
			LOGGER.debug("todo list moved while we were working, going straight round again");
			return;
		}
		waitForWork(timeoutMs);
	}

	/**
	 * Waits out {@code timeoutMs} whatever the todo list does, cut short only by this processor being
	 * stopped or terminated.
	 * <p>
	 * This is what a failing batch waits on, and it cannot be {@link #waitForWork}: bookmark moves are
	 * delivered as a plain {@code notify()} on this monitor, so a parked thread is woken by any of them
	 * however it came to be parked. Skipping the {@code monitoredBookmarkMoved} check alone would not
	 * hold a failing automation back — the very notification that sets that flag would release it — and
	 * the retry rate would follow whatever traffic is feeding the todo list instead of the backoff.
	 */
	private void backOff ( long timeoutMs ) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		try {
			synchronized ( this ) {
				while ( instanceMode != ProcessorInstanceMode.TERMINATING && processorMode != ProcessorMode.STOPPED ) {
					long remaining = deadline - System.currentTimeMillis();
					if ( remaining <= 0 ) {
						return;
					}
					this.wait(remaining);
				}
			}
		} catch (InterruptedException e) {
			LOGGER.debug("interrupted while backing off"); // see waitForWork for why the flag is not restored
		}
	}

	/**
	 * Parks this thread for at most {@code timeoutMs}, waking early on a bookmark move or on a state
	 * change. Re-checks for termination before parking: the {@code terminate()} that set TERMINATING may
	 * have notified while nothing was waiting to hear it, and parking anyway makes shutdown sit out the
	 * whole timeout.
	 */
	private void waitForWork ( long timeoutMs ) {
		if ( timeoutMs <= 0 ) {
			return; // Object.wait(0) waits forever, which is the opposite of what a zero delay asks for
		}
		try {
			synchronized ( this ) {
				if ( instanceMode != ProcessorInstanceMode.TERMINATING ) {
					this.wait(timeoutMs);
				}
			}
		} catch (InterruptedException e) {
			// deliberately not restoring the flag: this loop parks again on its next pass, and a set
			// flag would make that throw immediately and spin. An interrupt here comes from the thread
			// manager giving up on a terminate() that has already set TERMINATING, so the loop ends anyway
			LOGGER.debug("interrupted while waiting");
		}
	}

	/** What one batch did, and what the loop around it should do next. */
	private static class BatchOutcome {
		long streamed;
		long handled;
		long failed;
		EventReference lastProducedEvent;
		boolean stopAutomation;
		Throwable lastFailure;
	}

	/**
	 * What this automation is doing, for an operator. Read off the processor without synchronisation, so
	 * it is a snapshot rather than a consistent view — which is what {@code AutomationAdminCapability}
	 * documents it to be, and why restarting reports what it actually did.
	 */
	AutomationStatus status ( ) {
		return new AutomationStatus(
				processorIdentification.id(),
				automation.getClass().getSimpleName(),
				processorMode != ProcessorMode.STOPPED,
				itemsFailed.get(),
				consecutiveFailedBatches,
				failureOf(lastFailure),
				failureOf(stoppedBy));
	}

	/**
	 * Restarts this processor if it is stopped, so it picks its todo list up again from its bookmark.
	 *
	 * @return {@code true} if it was stopped and has been restarted, {@code false} if it was running
	 */
	boolean restart ( ) {
		if ( processorMode != ProcessorMode.STOPPED ) {
			LOGGER.debug("automation '{}' is already running, nothing to restart", processorIdentification);
			return false;
		}
		LOGGER.info("restarting automation '{}'", processorIdentification);
		stoppedBy = null; // it is running again; what stopped it stays available as lastFailure
		start(BoundedContextEvent.AutomationStartReason.RESTART);
		return true;
	}

	String automationId ( ) {
		return processorIdentification.id();
	}

	private static BoundedContextEvent.Failure failureOf ( Throwable failure ) {
		if ( failure == null ) {
			return null;
		}
		StringWriter stackTrace = new StringWriter();
		failure.printStackTrace(new PrintWriter(stackTrace));
		return new BoundedContextEvent.Failure(failure.getClass().getName(), failure.getMessage(), stackTrace.toString());
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
	
	Throwable determineRootCause ( Throwable t ) {
		if ( t.getCause() == null ) {
			return t;
		} else {
			return determineRootCause ( t.getCause() );
		}
	}
	
}
