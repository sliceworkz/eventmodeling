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

import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
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

	private static final Limit MAX_BATCH_SIZE = Limit.to(50); // TODO this should be configurable via a builder, avoid direct ctr
	private static final long WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS = 10000;
	private static final long WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS = 30000;

	private EventSource<DOMAIN_EVENT_TYPE> eventSource;
	private Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation;
	private ProcessorMode originalProcessorMode;
	private ProcessorMode processorMode;
	private ProcessorIdentification processorIdentification; // this is us
	private ProcessorIdentification monitoredProcessorIdentification; // this is the readmodel-building processor we will shadow

	private Function<Tracing, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationContextFactory;

	private ProcessorInstanceMode instanceMode = ProcessorInstanceMode.LEADER; // TOOD implement leader selection on processors
	private Instance instance;

	private final String boundedContext;
	private final MeterRegistry meterRegistry;
	private final Counter batchCounter;
	private final Counter itemsHandledCounter;
	private final Timer batchTimer;

	public AutomationProcessor ( ProcessorIdentification processorIdentification, ProcessorIdentification monitoredProcessorIdentification, EventStream<DOMAIN_EVENT_TYPE> eventSource, Function<Tracing, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationContextFactory, Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation, ProcessorMode processorMode, Instance instance, String boundedContext, MeterRegistry meterRegistry ) {
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

		// Initialize metrics with base tags
		Tags baseTags = Tags.of("context", boundedContext)
				.and("automation", processorIdentification.id());

		this.batchCounter = meterRegistry.counter("sliceworkz.eventmodeling.automation.batch", baseTags);
		this.itemsHandledCounter = meterRegistry.counter("sliceworkz.eventmodeling.automation.items.handled", baseTags);
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
		this.processorMode = originalProcessorMode;
		synchronized ( this ) { // escape the wait state if needed
			this.notify();
		}
	}

	@Override
	public void bookmarkUpdated (String reader, EventReference processedUntil ) {
		ProcessorIdentification processor = ProcessorIdentification.parse(reader);

		if ( processor.equals(monitoredProcessorIdentification)) {
			LOGGER.debug("monitored event processor {} moved bookmark to  {}", processor.toString(), processedUntil);
			
			// always of interest to us, as we'll probably be running behind now.
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
						
						Optional<EventReference> monitoredBookmark = eventSource.getBookmark(monitoredProcessorIdentification.toString()); // get position up until which the readmodel has been updated
						
						if ( monitoredBookmark.isPresent() && ( lastReference.isEmpty() || (monitoredBookmark.get().position() >= lastReference.get().position()) ) ) {
							LOGGER.debug("monitoredBookmark is at {}, our own bookmark is at {}, processing can continue", monitoredBookmark.get(), lastReference.orElse(null));
							
							try {
								if ( monitoredBookmark.isPresent() ) {
									LOGGER.debug("monitored bookmark is at {}", monitoredBookmark.get());
									
									Tracing tracing = Tracing.init(instance).channel(processorIdentification.type()).actor(processorIdentification.id());
									
									LOGGER.debug("starting processing of max {} items at a time", MAX_BATCH_SIZE);
		
									ItemCounter counter = new ItemCounter();

									// Create automation context with tracing for proper correlation
									AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context = automationContextFactory.apply(tracing);

									// Time the batch processing and count items
									Timer.Sample sample = Timer.start(meterRegistry);
									Optional<EventReference> lastProducedEvent = automation.getTodoList().streamItems(MAX_BATCH_SIZE).map(i->{counter.increment(); return i;}).map(item->automation.handle(item, context)).flatMap(Optional::stream).reduce((first,second)->second);
									sample.stop(batchTimer);

									// Record metrics
									batchCounter.increment();
									itemsHandledCounter.increment(counter.get());
		
									if ( lastProducedEvent.isPresent() ) {
										// set our position to the last event we produced, we won't do a new run until the readmodel has been updated
										eventSource.placeBookmark(processorIdentification.toString(), lastProducedEvent.get(), processorIdentification.toTags(instance));
									}
		
									if ( counter.get() < MAX_BATCH_SIZE.value() ) {
										LOGGER.debug("no new todo items to handle");
										LOGGER.debug("not directly querying again, waiting for {} seconds", (WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS/1000));
										try {
											synchronized ( this ) {
												this.wait(WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS);
											}
											LOGGER.debug("done waiting, or notified that readmodel was updated and new items could be present");
										} catch (InterruptedException e) {
											LOGGER.debug("interrupted while waiting");
										}
									} else {
										LOGGER.debug("not at end of list - doing new batch of {}", MAX_BATCH_SIZE);
									}
									
									
								} else {
									LOGGER.debug("monitored bookmark is not set yet, no readmodel to process");
			
								}
		
							} catch (Throwable t ) {
								Throwable r = determineRootCause(t);
								LOGGER.error("problem in handler: rootcause {} : {}", r.getClass(), r.getMessage(), r );
		
								LOGGER.warn("Stopping handler due to error: {}", t.getMessage());
								processorMode = ProcessorMode.STOPPED;
								
							}
						} else {
							// risk of handling item twice ...
							LOGGER.debug("monitoredBookmark is {}, our own bookmark is {}, processing cannot continue until readmodel has kept up", monitoredBookmark, lastReference);
							try {
								synchronized ( this ) {
									this.wait(WAIT_BEFORE_CHECKING_FOR_NEW_BOOKMARK_TIME_MS);
								}
								LOGGER.debug("done waiting, or notified that readmodel was updated and new items could be present");
							} catch (InterruptedException e) {
								LOGGER.debug("interrupted while waiting");
							}
						}
	
					} else {
						LOGGER.debug("we're not leader, not running on this instance");
					}
					
				} else {
					mustFetchBookmark = true;
					LOGGER.debug("not running, waiting for further instructions, checking back in {} seconds", (WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS/1000));
					try {
						// TODO maybe synchronize on other object than to allow notify() upon state change from STOPPED to RUNNING again, independently of notifies for new events in stream? 
						synchronized ( this ) {
							this.wait(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
						}
						LOGGER.debug("done waiting or notified, checking new instructions");
					} catch (InterruptedException e) {
						LOGGER.debug("interrupted while waiting in stopped state");
						mustFetchBookmark = true;
					}
				}
			} catch ( Throwable t ) {
				LOGGER.error("unexpected throwable during processor run: " + determineRootCause(t), t);
			}
			
		}
		LOGGER.info("{} gracefully terminated", processorIdentification.toString());
	}
	
	private class ItemCounter {
		long value;
		public long increment ( ) {
			value++;
			return get();
		}
		public long get ( ) {
			return value;
		}
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
