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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * What a processor loop parks on, and what that costs while it idles.
 * <p>
 * The cost is the point of the class: a virtual thread parked in {@code Object.wait()} pins its carrier
 * on Java 21 through 23, so every idle processor held a platform thread and the scheduler's
 * {@code maxPoolSize} became a ceiling on how many processors a JVM could park at all. The two
 * thread-count scenarios below measure that directly — platform threads are what
 * {@link ThreadMXBean#getThreadCount()} counts, virtual threads are not — one for {@link Parking}
 * and, as the control that proves the measurement can see the problem, one for the monitor wait it
 * replaces. The remaining scenarios pin the monitor semantics the loops were written against.
 */
public class ParkingTest {

	/** Enough that the growth from pinning cannot be mistaken for the scheduler warming up its carriers. */
	private static final int PARKED_THREADS = 64;
	private static final long LONG_ENOUGH_MS = 60_000;

	private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

	@Test
	void aVirtualThreadParkedOnParkingHoldsNoPlatformThread ( ) throws Exception {
		int before = threads.getThreadCount();
		List<Parking> parkings = new ArrayList<>();
		List<Thread> parked = new ArrayList<>();
		CountDownLatch aboutToPark = new CountDownLatch(PARKED_THREADS);
		for ( int i = 0; i < PARKED_THREADS; i++ ) {
			Parking parking = new Parking();
			parkings.add(parking);
			parked.add(Thread.ofVirtual().start(() -> {
				try {
					parking.park(LONG_ENOUGH_MS, () -> { aboutToPark.countDown(); return false; });
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
				}
			}));
		}
		assertTrue(aboutToPark.await(10, TimeUnit.SECONDS));
		awaitAllWaiting(parked);

		int growth = threads.getThreadCount() - before;
		parkings.forEach(Parking::wake);
		for ( Thread thread : parked ) {
			thread.join(10_000);
		}

		// a handful of carriers may be created for the virtual threads to run on at all; what must not
		// happen is one platform thread per parked virtual thread
		assertTrue(growth < PARKED_THREADS / 4,
				PARKED_THREADS + " virtual threads parked on Parking grew the platform thread count by " + growth + " -- a parked processor is pinning its carrier");
	}

	/**
	 * The control: the same measurement over the monitor wait that {@link Parking} replaces. Only on
	 * the JDKs where a monitor wait pins — JEP 491 removes the pinning in JDK 24 — since it exists to
	 * prove that the scenario above would fail if the loops went back to {@code Object.wait()}.
	 */
	@Test
	void aVirtualThreadParkedInAMonitorWaitHoldsAPlatformThreadOnJdk21To23 ( ) throws Exception {
		assumeTrue(Runtime.version().feature() < 24, "a monitor wait no longer pins its carrier from JDK 24 on");

		int before = threads.getThreadCount();
		List<Object> monitors = new ArrayList<>();
		List<Thread> parked = new ArrayList<>();
		CountDownLatch aboutToPark = new CountDownLatch(PARKED_THREADS);
		for ( int i = 0; i < PARKED_THREADS; i++ ) {
			Object monitor = new Object();
			monitors.add(monitor);
			parked.add(Thread.ofVirtual().start(() -> {
				synchronized ( monitor ) {
					aboutToPark.countDown();
					try {
						monitor.wait(LONG_ENOUGH_MS);
					} catch ( InterruptedException e ) {
						Thread.currentThread().interrupt();
					}
				}
			}));
		}
		assertTrue(aboutToPark.await(10, TimeUnit.SECONDS));
		awaitAllWaiting(parked);

		int growth = threads.getThreadCount() - before;
		for ( Object monitor : monitors ) {
			synchronized ( monitor ) {
				monitor.notifyAll();
			}
		}
		for ( Thread thread : parked ) {
			thread.join(10_000);
		}

		assertTrue(growth >= PARKED_THREADS / 2,
				PARKED_THREADS + " virtual threads in a monitor wait grew the platform thread count by only " + growth + " -- the measurement cannot see pinning here");
	}

	@Test
	void aWakeArrivingBeforeTheParkIsNotWaitedOut ( ) throws Exception {
		Parking parking = new Parking();
		AtomicBoolean pending = new AtomicBoolean();

		parking.wake(() -> pending.set(true)); // nobody parked: the flag is what carries the wake

		long start = System.nanoTime();
		parking.park(LONG_ENOUGH_MS, pending::get, () -> pending.set(false));
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		assertTrue(elapsedMs < 1_000, "a set flag must skip the park, took " + elapsedMs + " ms");
		assertFalse(pending.get(), "the flag is consumed under the lock");
	}

	@Test
	void aWakeEndsAPark ( ) throws Exception {
		Parking parking = new Parking();
		CountDownLatch woken = new CountDownLatch(1);
		Thread parked = Thread.ofVirtual().start(() -> {
			try {
				parking.park(LONG_ENOUGH_MS, () -> false);
				woken.countDown();
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		});
		awaitAllWaiting(List.of(parked));

		parking.wake();

		assertTrue(woken.await(5, TimeUnit.SECONDS), "a wake must end the park");
	}

	@Test
	void aZeroOrNegativeTimeoutReturnsAtOnce ( ) throws Exception {
		Parking parking = new Parking();
		long start = System.nanoTime();

		parking.park(0, () -> false);
		parking.park(-1, () -> false);

		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000, "unlike Object.wait(0), a zero timeout must not wait forever");
	}

	/** The backoff shape: a bare wake does not end it, the deadline or the reason lapsing does. */
	@Test
	void parkUntilHoldsThroughBareWakesUntilItsDeadline ( ) throws Exception {
		Parking parking = new Parking();
		long deadline = System.currentTimeMillis() + 600;
		AtomicInteger wakes = new AtomicInteger();
		Thread waker = Thread.ofVirtual().start(() -> {
			while ( System.currentTimeMillis() < deadline + 100 ) {
				parking.wake();
				wakes.incrementAndGet();
				Thread.onSpinWait();
			}
		});

		long start = System.currentTimeMillis();
		parking.parkUntil(deadline, () -> true);
		long parkedMs = System.currentTimeMillis() - start;
		waker.join();

		assertTrue(wakes.get() > 0, "the control wakes must have happened");
		assertTrue(parkedMs >= 500, "parkUntil was cut short by a bare wake after " + parkedMs + " ms");
	}

	@Test
	void parkUntilEndsWhenTheReasonToKeepParkingLapses ( ) throws Exception {
		Parking parking = new Parking();
		AtomicBoolean keepParking = new AtomicBoolean(true);
		CountDownLatch ended = new CountDownLatch(1);
		Thread parked = Thread.ofVirtual().start(() -> {
			try {
				parking.parkUntil(System.currentTimeMillis() + LONG_ENOUGH_MS, keepParking::get);
				ended.countDown();
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		});
		awaitAllWaiting(List.of(parked));

		parking.wake(() -> keepParking.set(false));

		assertTrue(ended.await(5, TimeUnit.SECONDS), "the reason lapsing, with a wake, must end parkUntil");
		assertEquals(0, ended.getCount());
	}

	/** Waits until every thread is parked, so the count measures parked threads and not threads still on their way. */
	private static void awaitAllWaiting ( List<Thread> threads ) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while ( System.currentTimeMillis() < deadline ) {
			if ( threads.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING || t.getState() == Thread.State.WAITING) ) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("not every thread reached its park in time");
	}

}
