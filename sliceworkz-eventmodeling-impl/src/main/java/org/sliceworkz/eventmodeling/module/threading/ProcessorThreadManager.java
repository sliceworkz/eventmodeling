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

public class ProcessorThreadManager<EVENT_TYPE> implements LifecycleCapability {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorThreadManager.class);
	
	private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
	
	private Collection<? extends Processor> processors;
	
	public ProcessorThreadManager ( String name, Collection<? extends Processor> processors ) {
		this.processors = processors;
		if (processors.size() > 0 ) {
			LOGGER.info("creating threads for {} processors in {}", processors.size(), name);

			ThreadFactory threadFactory = Thread.ofVirtual().name("processor-thread").factory(); // name will be overridden by processor itself
			ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory); // factory delivers virtual threads
			processors.forEach(executor::submit);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				LOGGER.info("shutting down threads for {}", name);
				terminate();
			    executor.shutdown();
			    try {
			        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			            executor.shutdownNow();
			        }
			    } catch (InterruptedException e) {
			        executor.shutdownNow();
			    }
			}, "ShutdownThread/" + name));
		} else {
			LOGGER.info("no processors needed for {}.", name);
		}
	}
	
	@Override
	public void start ( ) {
		processors.forEach(Processor::start);
	}
	
	@Override
	public void stop ( ) {
		processors.forEach(Processor::stop);
	}

	@Override
	public void terminate ( ) {
		processors.forEach(Processor::terminate);
	}

}