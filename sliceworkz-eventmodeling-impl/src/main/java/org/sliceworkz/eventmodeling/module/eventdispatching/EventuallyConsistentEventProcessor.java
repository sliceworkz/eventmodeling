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

import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.module.threading.Processor;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

public class EventuallyConsistentEventProcessor<EVENT_TYPE> implements EventStreamEventuallyConsistentAppendListener, Processor {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(EventuallyConsistentEventProcessor.class);
	
	private static final Limit MAX_BATCH_SIZE = Limit.to(10); // TODO this should be configurable via builder, avoid direct ctr
	private static final long WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS = 10000;
	private static final long WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS = 30000;
	
	private EventSource<EVENT_TYPE> eventSource;
	private EventQuery eventQuery;
	private ProcessorMode originalProcessorMode;
	private ProcessorMode processorMode;
	private EventWithMetaDataHandler<EVENT_TYPE> eventHandler;
	private EventuallyConsistentProcessorIdentification processorIdentification;
	
	private volatile EventReference lastReference;
	private volatile ProcessorInstanceMode instanceMode = ProcessorInstanceMode.LEADER; // TOOD implement leader selection on processors
	private Instance instance;
	
	public EventuallyConsistentEventProcessor ( EventuallyConsistentProcessorIdentification processorIdentification, EventSource<EVENT_TYPE> eventSource, EventQuery eventQuery, EventWithMetaDataHandler<EVENT_TYPE> eventHandler, ProcessorMode processorMode, Instance instance ) {
		this.eventSource = eventSource;
		this.eventQuery = eventQuery;
		this.eventHandler = eventHandler;
		this.originalProcessorMode = processorMode;
		this.processorMode = processorMode;
		this.processorIdentification = processorIdentification;
		this.instance = instance;
		
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
	public void eventsAppended(EventReference atLeastUntil) {
		LOGGER.debug("eventually consistent event processor notified of updates until at least {}", atLeastUntil);
		if ( lastReference == null || (atLeastUntil.position() > lastReference.position()) ) {
			// might be new interesting events.  in case we're wait()-ing, let's continue and query immediately to check!
			LOGGER.debug("might be new interesting events, querying them immediately!");
			synchronized ( this ) {
				this.notify();
			}
		} else {
			// nothing new to discover, we're already at this position in the stream with our processing
			LOGGER.debug("nothing new to process based on this update, already at {}", lastReference);
		}
	}
	
	@Override
	public void run ( ) {
		Thread.currentThread().setName(processorIdentification.id()); // make the thread easily recognizable
		LOGGER.info("eventually consistent event processor '{}' running ...", processorIdentification.toString());
		
		boolean mustFetchBookmark = true;
		
		if ( processorIdentification.storage() == Storage.EPHEMERAL ) {
			LOGGER.info("EPHEMERAL storage, processing stream from start ...");
			mustFetchBookmark = false; // ignore any existing bookmark that might be there from a previous process
		}
		
		while ( instanceMode != ProcessorInstanceMode.TERMINATING ) {
			synchronized(this) { // TODO check whether this is still strictly necessary
			try {
	
				// if instance is running ...
				if ( processorMode != ProcessorMode.STOPPED ) {
					
					if ( processorMode == ProcessorMode.RUNNING_ON_ALL_INSTANCES || instanceMode == ProcessorInstanceMode.LEADER ) {
	
						if ( mustFetchBookmark ) {
							lastReference = eventSource.getBookmark(processorIdentification.toString()).orElse(null); // get lastReference from bookmark of previous run
							if ( lastReference != null ) {
								LOGGER.debug("resuming at bookmark, last reference is {}", lastReference);
							} else {
								LOGGER.debug("no previous bookmark, starting from start of stream");
							}
							mustFetchBookmark = false; // as long as this thread is processing the next round, no need to go and fetch the bookmark again from storage
						}
						Stream<Event<EVENT_TYPE>> newEvents = eventSource.query(eventQuery, lastReference, MAX_BATCH_SIZE);
				
						try {
							Tracing.set(Tracing.init(instance).channel(processorIdentification.type()).actor(processorIdentification.id()));
							
							// handle all events (if any)
							Optional<HandledEvent<EVENT_TYPE>> lastHandled = handle(newEvents);
							if ( lastHandled.isPresent() ) {
								LOGGER.debug("{} new events handled, last reference is {}", lastHandled.get().count(), lastHandled.get().event().reference());
								// move pointer to last processed
								lastReference = lastHandled.get().event().reference();
								
								LOGGER.debug("placing bookmark to reference {}", lastHandled.get().event().reference());
								// place bookmark with lastReference
								eventSource.placeBookmark(processorIdentification.toString(), lastReference, processorIdentification.toTags(instance));
								
							} else {
								LOGGER.debug("no new events handled, last reference is {}", lastReference);
								// no events handled
								LOGGER.debug("not directly querying again, waiting for {} seconds", (WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS/1000));
								try {
									synchronized ( this ) {
										this.wait(WAIT_BEFORE_CHECKING_FOR_NEW_EVENTS_TIME_MS);
										LOGGER.debug("done waiting, or notified that new events could be present");
									}
								} catch (InterruptedException e) {
									LOGGER.debug("interrupted while waiting");
								}
							}
						} catch (Throwable t ) {
							
							// TODO what if we got an exception after a few items? in that case bookmark is pointing to a few correctly processed ones !
							
							//LOGGER.error("problem in handler: throwable {} : {}", t.getClass(), t.getMessage(), t ); // don't log the wrapped exception (reflection/proxy stuff are irrelevant)
							ThrowableAndEventReference r = ThrowableAndEventReference.determineRootCause(t);
							LOGGER.error("problem in handler for event {} : rootcause {} : {}", r.eventReference(), r.getClass(), r.throwable().getMessage(), r.throwable() );
	
							LOGGER.warn("Stopping handler due to error: {}", r.throwable().getMessage(), r.throwable());
							processorMode = ProcessorMode.STOPPED;
							
						} finally {
							Tracing.clear();
						}
					} else {
						LOGGER.debug("we're not leader, not running on this instance");
						//mustFetchBookmark = true; // next time, we'll need to see where the process is in the stream
					}
					
				} else {
					LOGGER.debug("not running, waiting for further instructions, checking back in {} seconds", (WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS/1000));
					mustFetchBookmark = true;
					try {
						// TODO maybe synchronize on other object than to allow notify() upon state change from STOPPED to RUNNING again, independently of notifies for new events in stream?
						synchronized ( this ) {
							this.wait(WAIT_BEFORE_CHECKING_NEW_INSTRUCTIONS_WHILE_STOPPED_TIME_MS);
						}
						LOGGER.debug("done waiting or notified, checking new instructions");
					} catch (InterruptedException e) {
						LOGGER.debug("interrupted while waiting in stopped state");
					}
				}
				
			} catch ( Throwable t ) {
				Throwable rootCause = ThrowableAndEventReference.determineRootCause(t).throwable();
				LOGGER.error("unexpected throwable during processor run: " + rootCause.getMessage() , rootCause);
			}
			}
				
		}
		LOGGER.info("{} gracefully terminated", processorIdentification.toString());
	}
	
	private Optional<HandledEvent<EVENT_TYPE>> handle ( Stream<Event<EVENT_TYPE>> newEvents ) {
		Counter counter = new Counter();
		Optional<HandledEvent<EVENT_TYPE>> lastHandled = newEvents.map(e->handle(e, counter)).reduce((first,second)->second);
		return lastHandled;
	}
	
	private HandledEvent<EVENT_TYPE> handle ( Event<EVENT_TYPE> newEvent, Counter counter ) {
		// call the actual event handler
		// TODO shall we do this in one stream, or one shot, so that eventhandler knows when to start/commit transactions, ...?  maybe extend EventHandler to something more? 
		try {
			eventHandler.when(newEvent);
		} catch (Throwable e) {
			ExceptionWhileHandlingEventWithReference.throwFor(e, newEvent.reference());
		}
		return new HandledEvent<EVENT_TYPE>(counter.increment(), newEvent);
	}
	
	private class Counter {
		long value;
		public long increment ( ) {
			value++;
			return get();
		}
		public long get ( ) {
			return value;
		}
	}
	
	private record HandledEvent<EVENT_TYPE> ( long count, Event<? extends EVENT_TYPE> event ) {
		
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
