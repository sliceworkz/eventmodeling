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
package org.sliceworkz.eventmodeling.module.threading;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;

/**
 * Owns the threads a module's processors run on, and does not create them until {@link #start()}.
 * <p>
 * <b>Construction is deliberately inert.</b> Submitting the processors from the constructor made
 * {@code build()} the moment the threads appeared, which is not what {@code build()} means: a context
 * that was built and never started ran a thread per registered processor, and a build that failed
 * part-way left the threads of the modules it had got through with nothing to reclaim them — the
 * shutdown hook and {@code terminate()} both live on the {@code BoundedContextImpl} that such a build
 * never constructs. Nothing was gained by it either, since every processor starts out
 * {@code STOPPED} and does no work until {@code start()} says so.
 * <p>
 * The threads are not free while they idle, which is what made this worth moving rather than merely
 * tidying: both processor loops park in {@code Object.wait()} inside a {@code synchronized} block, and
 * on Java 21 a monitor wait pins the carrier — so a parked virtual thread holds a platform thread for
 * as long as it waits.
 */
public class ProcessorThreadManager<EVENT_TYPE> implements LifecycleCapability {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorThreadManager.class);

	private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

	private final String name;
	private final Collection<? extends Processor> processors;

	/** Created by the first {@link #start()}, so a context that is never started creates no threads. */
	private ExecutorService executor;

	private boolean threadsSubmitted;
	private boolean terminated;

	public ProcessorThreadManager ( String name, Collection<? extends Processor> processors ) {
		this.name = name;
		this.processors = processors;
	}

	/**
	 * Starts the processors and, the first time, puts them on threads.
	 * <p>
	 * The processors are started <em>before</em> their threads are submitted, and that order matters:
	 * {@link Processor#start()} signals a thread that may not be parked yet, and the stopped branch of
	 * a processor loop re-checks only the terminating flag before waiting, so a signal arriving too
	 * early would be lost and the processor would sit out its whole poll interval before noticing it
	 * had been started. Starting first means the loop reads a processor that is already running on its
	 * very first pass, with nothing to signal and nothing to lose. On a restart after {@link #stop()}
	 * the threads are still parked and are simply signalled, which is why they are submitted once.
	 */
	@Override
	public synchronized void start ( ) {
		processors.forEach(Processor::start);
		if ( threadsSubmitted || terminated ) {
			return;
		}
		threadsSubmitted = true; // once, whether or not there is anything to put on a thread
		if ( processors.isEmpty() ) {
			LOGGER.info("no processors needed for {}.", name);
		} else {
			LOGGER.info("creating threads for {} processors in {}", processors.size(), name);
			ThreadFactory threadFactory = Thread.ofVirtual().name("processor-thread").factory(); // name will be overridden by processor itself
			this.executor = Executors.newThreadPerTaskExecutor(threadFactory); // factory delivers virtual threads
			processors.forEach(executor::submit);
		}
	}

	@Override
	public void stop ( ) {
		processors.forEach(Processor::stop);
	}

	/**
	 * Terminates the processors and drains their thread pool. Invoked through the bounded context's
	 * shutdown sequence (which registers a single JVM shutdown hook) rather than a per-manager hook.
	 * <p>
	 * There may be no pool to drain: a context that was built and never started has no threads, and a
	 * build that failed terminates what it had constructed for the same reason. The processors are
	 * still told, so that a later {@link #start()} cannot put a terminated set of them back on threads.
	 */
	@Override
	public synchronized void terminate ( ) {
		terminated = true;
		processors.forEach(Processor::terminate);
		if (executor != null) {
			LOGGER.info("shutting down threads for {}", name);
			executor.shutdown();
			try {
				if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

}