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
package org.sliceworkz.eventmodeling.ports;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The monitored port call running on this thread, which an adapter reports what it consumed to:
 * <pre>{@code
 * var sent = sms.send(to, text);
 * PortCall.current().consumed(Consumption.of("twilio", "sms").quantity("segments", sent.segments()));
 * }</pre>
 * The framework opens one around every call through a monitored port, before the adapter runs, and puts what
 * was reported on the call's port event ({@code PortCalled}, {@code PortCallRejected}, {@code PortCallFailed}, or
 * summed in {@code PortCallsSummarized}) and its observation. Report before throwing when a failed call was billed
 * too: a refusal usually is.
 * <p>
 * Outside a monitored call — an adapter driven by its own test, behind a binding without {@code monitored()}, or
 * on a thread the call did not start — {@link #current()} answers a call that records nothing, so an adapter
 * reports unconditionally. An adapter handing its work to another thread takes {@code PortCall.current()} first and
 * reports through that handle; a report arriving after the call ended is dropped with a warning, never attached to
 * a later call.
 * <p>
 * The alternatives — the port's return type carrying the consumption, or an annotation saying where to find it —
 * lose because a call that throws is billed too and has no return value, and because what a call costs is a
 * property of one adapter (one model, another, a stand-in) rather than of the port every adapter implements.
 */
public final class PortCall {

	private static final Logger LOGGER = LoggerFactory.getLogger(PortCall.class);

	private static final ThreadLocal<PortCall> CURRENT = new ThreadLocal<>();
	private static final PortCall NONE = new PortCall(null);

	private final Consumer<Consumption> sink;
	private volatile boolean ended;

	private PortCall ( Consumer<Consumption> sink ) {
		this.sink = sink;
	}

	/** The port call running on this thread; one that records nothing when there is none. */
	public static PortCall current ( ) {
		PortCall call = CURRENT.get();
		return call == null ? NONE : call;
	}

	/** Whether this is a monitored call, whose consumption is recorded. */
	public boolean monitored ( ) {
		return sink != null;
	}

	/**
	 * Reports what the call consumed. May be called several times (a retry, a fallback, two requests): each
	 * report is kept.
	 *
	 * @return this call, to report more
	 */
	public PortCall consumed ( Consumption consumption ) {
		if ( sink == null || consumption == null ) {
			return this;
		}
		if ( ended ) {
			LOGGER.warn("consumption reported after its port call ended is dropped: {}/{} {}", consumption.provider(),
					consumption.billedProduct(), consumption.quantities());
			return this;
		}
		try {
			sink.accept(consumption);
		} catch ( RuntimeException e ) {
			LOGGER.error("could not record consumption {}/{} - the call itself is unaffected", consumption.provider(), consumption.billedProduct(), e);
		}
		return this;
	}

	/**
	 * Opens a port call on this thread whose consumption goes to the sink, until the returned handle is closed:
	 * what the framework does around a monitored adapter, and what an adapter's own test does to see what the
	 * adapter reports. The call it replaces, if any, is current again once the handle is closed.
	 */
	public static Opened open ( Consumer<Consumption> sink ) {
		PortCall call = new PortCall(sink);
		PortCall previous = CURRENT.get();
		CURRENT.set(call);
		return new Opened(call, previous);
	}

	/** An open port call; closing it ends the call on the thread that opened it. */
	public static final class Opened implements AutoCloseable {

		private final PortCall call;
		private final PortCall previous;
		private final Thread thread = Thread.currentThread();

		private Opened ( PortCall call, PortCall previous ) {
			this.call = call;
			this.previous = previous;
		}

		public PortCall call ( ) {
			return call;
		}

		@Override
		public void close ( ) {
			call.ended = true;
			if ( Thread.currentThread() == thread && CURRENT.get() == call ) {
				if ( previous == null ) {
					CURRENT.remove();
				} else {
					CURRENT.set(previous);
				}
			}
		}
	}
}
