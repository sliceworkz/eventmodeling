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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * What a processor loop parks on between rounds, and what wakes it: a {@link ReentrantLock} with one
 * {@link Condition}, in place of the loop object's own monitor.
 * <p>
 * <b>The choice of lock is what makes an idle processor free.</b> The processors run on virtual
 * threads, and on Java 21 through 23 a virtual thread that blocks inside a {@code synchronized} block
 * — which is what {@code Object.wait()} is — pins its carrier: the platform thread underneath stays
 * blocked with it, and the scheduler compensates by adding a carrier, up to
 * {@code jdk.virtualThreadScheduler.maxPoolSize} (256 by default). A processor parked that way costs a
 * platform thread for as long as it idles, and a deployment with more processors than that ceiling —
 * read models, translators, dispatchers and automations across co-located contexts — has processors
 * that are never scheduled at all, with nothing failing to say so. A {@code Condition} parks through
 * {@code LockSupport}, which unmounts the virtual thread, so a parked processor holds nothing, on any
 * JDK.
 * <p>
 * Two alternatives lose. Keeping the monitors and relying on JDK 24, where JEP 491 removes the
 * pinning, loses because this library is built for release 21 and runs on whatever its consumers
 * run. Platform threads for the processors — one per processor, a bounded number — would not pin
 * anything either, but buy nothing over a virtual thread that costs nothing while parked, and pay a
 * full thread stack for every idle processor.
 * <p>
 * <b>The semantics are those of the monitor, so a loop reads as it would with {@code wait} and
 * {@code notify}.</b> A {@link #wake()} that finds nobody parked is lost, exactly as a
 * {@code notify()} is: every waker sets its flag before waking, and every parker re-checks its flags
 * under the lock before parking ({@link #park}), which is what keeps a wake arriving between the check
 * and the park from being lost. Waking is {@code signalAll}, since a processor has one loop thread and
 * nothing is gained by singling it out. One difference is deliberate: a timeout of zero or less
 * returns at once, where {@code Object.wait(0)} waits forever — a zero delay asks for no wait, and
 * the only caller that could pass one guards against it anyway.
 */
public final class Parking {

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition woken = lock.newCondition();

	/**
	 * Wakes the parked loop, if it is parked. Lost otherwise, as a {@code notify()} is — the caller has
	 * set whatever flag the loop re-checks before it parks.
	 */
	public void wake ( ) {
		lock.lock();
		try {
			woken.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Runs {@code beforeWaking} under the lock and then wakes the parked loop, so a flag set there and
	 * the wake are one step: a parker checking that flag under the same lock (see {@link #park}) sees
	 * either the flag set or the wake, never neither.
	 */
	public void wake ( Runnable beforeWaking ) {
		lock.lock();
		try {
			beforeWaking.run();
			woken.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Parks for at most {@code timeoutMs}, unless {@code reasonNotTo} holds — checked under the lock, so
	 * a wake that arrived before the check is not waited out and one arriving after it is not lost.
	 * Returns on a wake, on the timeout, or at once when the reason held or the timeout is zero or
	 * less; the caller re-reads its state either way, as after a {@code wait()}.
	 */
	public void park ( long timeoutMs, BooleanSupplier reasonNotTo ) throws InterruptedException {
		park(timeoutMs, reasonNotTo, () -> { });
	}

	/**
	 * {@link #park(long, BooleanSupplier)}, then {@code afterwards} under the same lock whether or not
	 * it parked — for consuming the flag a waker set with {@link #wake(Runnable)}, so that a flag set
	 * while the loop was parked is cleared before the lock is released and cannot be overwritten by a
	 * wake that lands in between.
	 */
	public void park ( long timeoutMs, BooleanSupplier reasonNotTo, Runnable afterwards ) throws InterruptedException {
		lock.lock();
		try {
			if ( timeoutMs > 0 && !reasonNotTo.getAsBoolean() ) {
				woken.await(timeoutMs, TimeUnit.MILLISECONDS);
			}
			afterwards.run();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Parks until {@code deadlineMs} (on {@link System#currentTimeMillis()}) for as long as
	 * {@code keepParking} holds, parking again after every wake that leaves it holding — the shape of a
	 * backoff, which a bare wake must not cut short: the loops are woken by every append and bookmark
	 * notification on their stream, however they came to be parked, and a failing processor released by
	 * traffic would retry at the pace of the very stream feeding it. Only the deadline, or the reason to
	 * keep parking lapsing, ends it.
	 */
	public void parkUntil ( long deadlineMs, BooleanSupplier keepParking ) throws InterruptedException {
		lock.lock();
		try {
			while ( keepParking.getAsBoolean() ) {
				long remaining = deadlineMs - System.currentTimeMillis();
				if ( remaining <= 0 ) {
					return;
				}
				woken.await(remaining, TimeUnit.MILLISECONDS);
			}
		} finally {
			lock.unlock();
		}
	}

}
