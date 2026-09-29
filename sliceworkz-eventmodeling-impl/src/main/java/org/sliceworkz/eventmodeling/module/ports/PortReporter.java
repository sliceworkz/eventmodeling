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
package org.sliceworkz.eventmodeling.module.ports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.ports.PortCaller;
import org.sliceworkz.eventmodeling.ports.PortLatencyBuckets;

/**
 * Reports the calls of a bounded context's monitored ports: to the observer, every call, and to the
 * monitoring stream as the binding's {@code PortMonitoring} says — an event per call, or a
 * {@code PortCallsSummarized} per port, method and caller per interval.
 * <p>
 * Everything a call reports is reported after the call has returned, and the time blocked is measured
 * before it: the append a per-call event costs is never part of the duration it reports. Reporting never
 * fails a call — the emitter and the observer are contained, and so is what happens here.
 *
 * <h2>Summary windows</h2>
 * A window is aligned to the wall clock — {@code [floor(now / interval) * interval, + interval)} — so the
 * windows of several instances of a deployment cover the same span and a dashboard adds them up. Calls are
 * counted on the caller's thread; the windows are emitted by a thread of this reporter's own, which
 * {@link #start()} creates, once a window has ended. A call arriving for a window that has ended but was
 * not emitted yet sets the ended window aside for that thread and starts the next, so no call is ever
 * counted in the wrong window. {@link #flush()} emits every window holding calls, the unfinished one
 * included, which is what stopping a context does so the last partial window is not lost.
 * <p>
 * On a summarized port a failure is still emitted on its own — the first of each exception type per
 * method and caller per window — and counted in the window like every other call.
 */
public final class PortReporter {

	private static final Logger LOGGER = LoggerFactory.getLogger(PortReporter.class);

	/** How often the reporter's thread looks for windows that have ended. */
	static final Duration TICK = Duration.ofSeconds(1);

	private final String boundedContext;
	private final BoundedContextEventEmitter emitter;
	private final BoundedContextObserver observer;
	private final Clock clock;

	private final Map<WindowKey, Window> windows = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<BoundedContextEvent.PortCallsSummarized> ended = new ConcurrentLinkedQueue<>();

	private final Object lifecycle = new Object();
	private Thread flusher;
	private volatile boolean terminated;

	public PortReporter ( String boundedContext, BoundedContextEventEmitter emitter, BoundedContextObserver observer, Clock clock ) {
		this.boundedContext = boundedContext;
		this.emitter = emitter;
		this.observer = observer;
		this.clock = clock == null ? Clock.systemUTC() : clock;
	}

	/**
	 * Starts the thread that emits ended summary windows. Idempotent; only called for a context that
	 * summarizes at least one port.
	 */
	public void start ( ) {
		synchronized ( lifecycle ) {
			if ( flusher != null || terminated ) {
				return;
			}
			flusher = Thread.ofVirtual().name("port-summaries-" + boundedContext).start(this::run);
		}
	}

	/**
	 * Emits every window holding calls, the unfinished ones included.
	 */
	public void flush ( ) {
		emitEnded();
		Instant now = clock.instant();
		for ( Map.Entry<WindowKey, Window> entry : windows.entrySet() ) {
			BoundedContextEvent.PortCallsSummarized summary = entry.getValue().drain(entry.getKey(), now, true);
			if ( summary != null ) {
				emit(summary);
			}
		}
	}

	/**
	 * Emits what is left and ends the reporter's thread.
	 */
	public void terminate ( ) {
		Thread thread;
		synchronized ( lifecycle ) {
			terminated = true;
			thread = flusher;
			flusher = null;
		}
		if ( thread != null ) {
			thread.interrupt();
			try {
				thread.join(TICK.toMillis() * 5);
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}
		flush();
	}

	private void run ( ) {
		while ( !terminated ) {
			try {
				Thread.sleep(TICK);
			} catch ( InterruptedException e ) {
				return;
			}
			try {
				emitEnded();
				Instant now = clock.instant();
				for ( Map.Entry<WindowKey, Window> entry : windows.entrySet() ) {
					BoundedContextEvent.PortCallsSummarized summary = entry.getValue().drain(entry.getKey(), now, false);
					if ( summary != null ) {
						emit(summary);
					}
				}
			} catch ( RuntimeException e ) {
				LOGGER.error("could not report the port call summaries of bounded context '{}'", boundedContext, e);
			}
		}
	}

	private void emitEnded ( ) {
		BoundedContextEvent.PortCallsSummarized summary;
		while ( (summary = ended.poll()) != null ) {
			emit(summary);
		}
	}

	private void emit ( BoundedContextEvent.PortCallsSummarized summary ) {
		emitter.emit(summary);
	}

	/**
	 * Starts reporting one call. Called on the caller's thread, before the adapter is: the observation is
	 * current while the call runs, so whatever the adapter does is nested beneath it.
	 */
	Call start ( MonitoredPort port, String method ) {
		PortCallerScope.Current current = PortCallerScope.current();
		Observation.Scope<Outcome.PortCallOutcome> scope = observer.start(new Observation.PortCall(
				boundedContext, port.portType().getSimpleName(), port.qualification(), method, current.caller(), current.tracing()));
		return new Call(port, method, current, scope);
	}

	/** One call in progress, finished by exactly one of {@link #returned} and {@link #threw}. */
	final class Call {

		private final MonitoredPort port;
		private final String method;
		private final PortCallerScope.Current caller;
		private final Observation.Scope<Outcome.PortCallOutcome> scope;

		private Call ( MonitoredPort port, String method, PortCallerScope.Current caller, Observation.Scope<Outcome.PortCallOutcome> scope ) {
			this.port = port;
			this.method = method;
			this.caller = caller;
			this.scope = scope;
		}

		void returned ( long micros ) {
			try {
				scope.completed(new Outcome.PortReturned());
			} finally {
				scope.close();
			}
			report(() -> {
				if ( port.monitoring().isSummarized(method) ) {
					count(Kind.CALLED, micros, null);
				} else {
					emitter.emit(new BoundedContextEvent.PortCalled(boundedContext, portName(), port.qualification(), method,
							caller.caller(), micros, slice()), caller.tracing());
				}
			});
		}

		void threw ( Throwable thrown, long micros ) {
			boolean business = port.monitoring().isBusinessException(thrown);
			try {
				if ( business ) {
					scope.completed(new Outcome.PortRejected(thrown.getClass().getName(), thrown.getMessage()));
				} else {
					scope.failed(thrown);
				}
			} finally {
				scope.close();
			}
			report(() -> {
				boolean summarized = port.monitoring().isSummarized(method);
				if ( business ) {
					if ( summarized ) {
						count(Kind.REJECTED, micros, null);
					} else {
						emitter.emit(new BoundedContextEvent.PortCallRejected(boundedContext, portName(), port.qualification(), method,
								caller.caller(), micros, thrown.getClass().getName(), thrown.getMessage(), slice()), caller.tracing());
					}
				} else {
					boolean emitOnItsOwn = !summarized || count(Kind.FAILED, micros, thrown.getClass().getName());
					if ( emitOnItsOwn ) {
						emitter.emit(new BoundedContextEvent.PortCallFailed(boundedContext, portName(), port.qualification(), method,
								caller.caller(), micros, BoundedContextEvent.Failure.of(thrown), slice(), summarized), caller.tracing());
					}
				}
			});
		}

		private void report ( Runnable reporting ) {
			if ( !emitter.enabled() ) {
				return;
			}
			try {
				reporting.run();
			} catch ( RuntimeException e ) {
				LOGGER.error("could not report a call of port '{}' in bounded context '{}' - the call itself is unaffected", portName(), boundedContext, e);
			}
		}

		/** @return for a failure, whether it is the first of its type in this window */
		private boolean count ( Kind kind, long micros, String failureType ) {
			WindowKey key = new WindowKey(port, method, caller.caller(), caller.componentClass());
			Window window = windows.computeIfAbsent(key, k -> new Window(port.monitoring().interval()));
			return window.count(key, clock.instant(), kind, micros, failureType);
		}

		private String portName ( ) {
			return port.portType().getSimpleName();
		}

		private BoundedContextEvent.FeatureSlice slice ( ) {
			return caller.componentClass() == null ? null : emitter.sliceFor(caller.componentClass());
		}
	}

	private enum Kind { CALLED, REJECTED, FAILED }

	private record WindowKey ( MonitoredPort port, String method, PortCaller caller, Class<?> componentClass ) {
		@Override
		public boolean equals ( Object other ) {
			return other instanceof WindowKey k && k.port == port && k.method.equals(method)
					&& Objects.equals(k.caller, caller) && k.componentClass == componentClass;
		}

		@Override
		public int hashCode ( ) {
			return Objects.hash(System.identityHashCode(port), method, caller, componentClass);
		}
	}

	/**
	 * The start of the wall-clock aligned window an instant falls in.
	 */
	static Instant windowStart ( Instant at, Duration interval ) {
		long intervalMs = interval.toMillis();
		long ms = at.toEpochMilli();
		return Instant.ofEpochMilli(Math.floorDiv(ms, intervalMs) * intervalMs);
	}

	/** The counts of one port, method and caller in the current window. */
	private final class Window {

		private final Duration interval;
		private Instant start;
		private long called;
		private long rejected;
		private long failed;
		private long totalMicros;
		private long maxMicros;
		private long[] buckets = new long[PortLatencyBuckets.COUNT];
		private final Set<String> failureTypesReported = new HashSet<>();

		Window ( Duration interval ) {
			this.interval = interval;
		}

		synchronized boolean count ( WindowKey key, Instant now, Kind kind, long micros, String failureType ) {
			Instant current = windowStart(now, interval);
			if ( start == null ) {
				start = current;
			} else if ( !start.equals(current) ) {
				// the window this call belongs to is not the one being counted: set that one aside for the
				// reporter's thread, and start counting this one
				BoundedContextEvent.PortCallsSummarized summary = summarize(key, start.plus(interval));
				if ( summary != null ) {
					ended.add(summary);
				}
				reset(current);
			}
			switch ( kind ) {
				case CALLED -> called++;
				case REJECTED -> rejected++;
				case FAILED -> failed++;
			}
			totalMicros += micros;
			maxMicros = Math.max(maxMicros, micros);
			buckets[PortLatencyBuckets.indexOf(micros)]++;
			return failureType != null && failureTypesReported.add(failureType);
		}

		/**
		 * @param partial emit the window even if it has not ended yet, as of {@code now}
		 * @return the window's summary, or {@code null} when there is nothing to emit yet
		 */
		synchronized BoundedContextEvent.PortCallsSummarized drain ( WindowKey key, Instant now, boolean partial ) {
			if ( start == null || called + rejected + failed == 0 ) {
				return null;
			}
			Instant end = start.plus(interval);
			if ( now.isBefore(end) ) {
				if ( !partial ) {
					return null;
				}
				end = now.isAfter(start) ? now : start;
			}
			BoundedContextEvent.PortCallsSummarized summary = summarize(key, end);
			reset(windowStart(now, interval));
			return summary;
		}

		private BoundedContextEvent.PortCallsSummarized summarize ( WindowKey key, Instant end ) {
			if ( called + rejected + failed == 0 ) {
				return null;
			}
			List<Long> histogram = new ArrayList<>(buckets.length);
			for ( long bucket : buckets ) {
				histogram.add(bucket);
			}
			return new BoundedContextEvent.PortCallsSummarized(boundedContext, key.port().portType().getSimpleName(), key.port().qualification(),
					key.method(), key.caller(), start, end, called, rejected, failed, totalMicros, maxMicros, histogram,
					key.componentClass() == null ? null : emitter.sliceFor(key.componentClass()));
		}

		private void reset ( Instant newStart ) {
			start = newStart;
			called = 0;
			rejected = 0;
			failed = 0;
			totalMicros = 0;
			maxMicros = 0;
			buckets = new long[PortLatencyBuckets.COUNT];
			failureTypesReported.clear();
		}
	}

}
