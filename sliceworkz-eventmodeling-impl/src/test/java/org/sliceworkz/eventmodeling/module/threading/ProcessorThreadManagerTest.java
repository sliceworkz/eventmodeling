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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Who creates the processor threads, and when.
 * <p>
 * The whole point of this class is that {@code build()} is not that moment: a bounded context that is
 * built and never started must cost no threads, and a build that fails part-way must leave none behind
 * — it hands the caller no context, so nothing would ever reclaim them.
 */
public class ProcessorThreadManagerTest {

	private static final long BRIEFLY_MS = 500;

	@Test
	void constructionRunsNothing ( ) throws Exception {
		RecordingProcessor processor = new RecordingProcessor();

		new ProcessorThreadManager<>("readmodel", List.of(processor));

		assertFalse(processor.ran(BRIEFLY_MS), "constructing the manager must not put its processors on threads");
		assertEquals(0, processor.starts.get(), "nor start them");
	}

	@Test
	void startPutsThemOnThreads ( ) throws Exception {
		RecordingProcessor processor = new RecordingProcessor();
		ProcessorThreadManager<Object> manager = new ProcessorThreadManager<>("readmodel", List.of(processor));

		manager.start();

		assertTrue(processor.ran(5000), "start() must run the processors");
		manager.terminate();
	}

	/**
	 * A processor is started before its thread is submitted, because {@link Processor#start()} signals a
	 * thread that may not be parked yet: the stopped branch of a processor loop re-checks only its
	 * terminating flag before waiting, so a signal arriving first would be lost and the processor would
	 * sit out a whole poll interval before noticing it had been started.
	 */
	@Test
	void aProcessorIsAlreadyStartedWhenItsThreadRuns ( ) throws Exception {
		RecordingProcessor processor = new RecordingProcessor();
		ProcessorThreadManager<Object> manager = new ProcessorThreadManager<>("readmodel", List.of(processor));

		manager.start();

		assertTrue(processor.ran(5000));
		assertTrue(processor.startedBeforeRun.get(), "start() must reach the processor before its thread does");
		manager.terminate();
	}

	@Test
	void threadsAreSubmittedOnce ( ) throws Exception {
		RecordingProcessor processor = new RecordingProcessor();
		ProcessorThreadManager<Object> manager = new ProcessorThreadManager<>("readmodel", List.of(processor));

		manager.start();
		assertTrue(processor.ran(5000));

		manager.stop();
		manager.start(); // the restart path: the thread is still parked and is only signalled

		Thread.sleep(BRIEFLY_MS);
		assertEquals(1, processor.runs.get(), "a restart must signal the parked thread, not submit a second one");
		assertEquals(2, processor.starts.get());
		manager.terminate();
	}

	@Test
	void terminatingWithoutStartingIsHarmless ( ) {
		RecordingProcessor processor = new RecordingProcessor();

		new ProcessorThreadManager<>("readmodel", List.of(processor)).terminate(); // no pool to drain

		assertEquals(1, processor.terminates.get(), "the processors are still told, even with no thread to stop");
	}

	@Test
	void startingAfterTerminatingRunsNothing ( ) throws Exception {
		RecordingProcessor processor = new RecordingProcessor();
		ProcessorThreadManager<Object> manager = new ProcessorThreadManager<>("readmodel", List.of(processor));

		manager.terminate();
		manager.start();

		assertFalse(processor.ran(BRIEFLY_MS), "a terminated set of processors must not be put back on threads");
	}

	@Test
	void aManagerWithNoProcessorsStartsAndTerminatesCleanly ( ) {
		ProcessorThreadManager<Object> manager = new ProcessorThreadManager<>("dispatcher", List.of());

		manager.start();
		manager.stop();
		manager.terminate();
	}

	static class RecordingProcessor implements Processor {

		final AtomicInteger runs = new AtomicInteger();
		final AtomicInteger starts = new AtomicInteger();
		final AtomicInteger terminates = new AtomicInteger();
		final AtomicBoolean startedBeforeRun = new AtomicBoolean();

		private final CountDownLatch running = new CountDownLatch(1);
		private volatile boolean terminating;

		boolean ran ( long timeoutMs ) throws InterruptedException {
			return running.await(timeoutMs, TimeUnit.MILLISECONDS);
		}

		@Override
		public void run ( ) {
			startedBeforeRun.set(starts.get() > 0);
			runs.incrementAndGet();
			running.countDown();
			while ( !terminating ) {           // stands in for the real processors' wait loop
				synchronized ( this ) {
					try {
						wait(100);
					} catch ( InterruptedException interrupted ) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		}

		@Override
		public void start ( ) {
			starts.incrementAndGet();
		}

		@Override
		public void stop ( ) {
		}

		@Override
		public void terminate ( ) {
			terminates.incrementAndGet();
			terminating = true;
			synchronized ( this ) {
				notify();
			}
		}
	}

}
