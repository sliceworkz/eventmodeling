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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Tags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Internal helper that turns a {@link BoundedContextEvent} into a tagged {@link EphemeralEvent} and
 * delivers it to the configured {@link BoundedContextListener}.
 * <p>
 * A single emitter is created per bounded context and shared by all kernel modules. When no listener
 * is registered (the {@link BoundedContextListener#NO_OP} sentinel) {@link #enabled()} returns
 * {@code false} and callers should skip building events entirely so there is no overhead on the hot
 * path.
 * <p>
 * <strong>A listener failure is never the caller's failure, and never silent.</strong> Every
 * emission is contained here: the listener's exception is caught, counted on
 * {@code sliceworkz.eventmodeling.listener.failure} and logged at ERROR, and the operation that
 * produced the event carries on as if no listener were registered. This is not defensive tidiness,
 * it is the only correct behaviour at three call sites:
 * <ul>
 *   <li>{@code CommandExecuted} is emitted <em>after</em> the command's domain events are durably
 *       appended. An escaping throwable would report a command that succeeded as failed — and the
 *       caller's natural response to that is to execute it again. Worse, the throw lands in
 *       {@code DCBModule}'s own {@code catch ( RuntimeException )}, which emits
 *       {@code CommandFailed} for the very same successful command.</li>
 *   <li>In {@code AutomationProcessor}, {@code AutomationProcessed} is emitted <em>before</em> the
 *       processor bookmarks the events it produced. A throw there skips the bookmark, so every item
 *       in the batch is handed to the automation again.</li>
 *   <li>A projector's run listener ({@code EventuallyConsistentReadModelUpdated}) runs inside the
 *       processor loop, where a throwable derails the projection rather than the observability.</li>
 * </ul>
 * Observability must never be able to fail the work it observes. This mirrors the event store's rule
 * for its own append and bookmark listeners, and like there, {@code Error} is deliberately not
 * caught — an exhausted heap or a {@code StackOverflowError} is not a listener problem to absorb.
 * <p>
 * Nothing replays what a failing listener missed: the event is dropped and the next one is delivered
 * normally. A listener that must not lose events has to make itself durable.
 * <p>
 * <strong>Logging is throttled, because this sits on the hot path of every command.</strong> A
 * listener broken by a storage outage fails once per command, and a stack trace each would bury the
 * cause under its own symptoms. The first failure of a run is logged in full; identical repeats are
 * counted and summarised at most once per {@value #FAILURE_SUMMARY_INTERVAL_MS} ms, a different
 * exception type reports immediately, and the recovery is logged too. The meter is never throttled,
 * so the true rate is always available there.
 */
public final class BoundedContextEventEmitter {

	private static final Logger LOGGER = LoggerFactory.getLogger(BoundedContextEventEmitter.class);

	/** Counts every failed delivery, tagged {@code context} and {@code event}. Never throttled. */
	static final String FAILURE_METER = "sliceworkz.eventmodeling.listener.failure";

	/** How long a run of identical failures stays quiet between summary lines. */
	static final long FAILURE_SUMMARY_INTERVAL_MS = 60_000;

	private final BoundedContextListener listener;
	private final Instance instance;
	private final SliceRegistry sliceRegistry;
	private final String boundedContext;
	private final MeterRegistry meterRegistry;

	/**
	 * One counter per {@link BoundedContextEvent} type. Bounded by construction - the event hierarchy
	 * is sealed - so this is not a cardinality risk.
	 */
	private final ConcurrentHashMap<String,Counter> failureCounters = new ConcurrentHashMap<>();

	private final Object failureLogLock = new Object();
	/** Written under {@link #failureLogLock}; volatile so the success path can check it without locking. */
	private volatile long consecutiveFailures = 0;
	private Class<?> lastLoggedFailureType;
	private long lastLoggedAtMs;
	private long suppressedSinceLastLog;

	public BoundedContextEventEmitter ( BoundedContextListener listener, Instance instance, SliceRegistry sliceRegistry,
			String boundedContext, MeterRegistry meterRegistry ) {
		this.listener = listener == null ? BoundedContextListener.NO_OP : listener;
		this.instance = instance;
		this.sliceRegistry = sliceRegistry;
		this.boundedContext = boundedContext;
		this.meterRegistry = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;
	}

	/**
	 * @return {@code true} when a real listener is registered, {@code false} for the no-op sentinel.
	 */
	public boolean enabled ( ) {
		return listener != BoundedContextListener.NO_OP;
	}

	/**
	 * Resolves the feature slice owning the given component class (by package convention), or
	 * {@code null} when the component is not located within a known slice package.
	 */
	public BoundedContextEvent.FeatureSlice sliceFor ( Class<?> componentClass ) {
		return sliceRegistry.resolve(componentClass);
	}

	/**
	 * Describes a feature slice - deployed or not - for the lifecycle inventory, including the
	 * members it registered.
	 */
	public BoundedContextEvent.FeatureSlice describe ( Slice<?> slice ) {
		return sliceRegistry.describe(slice);
	}

	/**
	 * Wraps the given event with system ({@code kernel}) tracing tags and delivers it to the
	 * listener. Use this for events not triggered by a specific operation (e.g. lifecycle). Does
	 * nothing when no listener is registered.
	 */
	public void emit ( BoundedContextEvent event ) {
		emit(event, null);
	}

	/**
	 * Wraps the given event with tracing tags and delivers it to the listener. The {@code actor},
	 * {@code channel} and {@code command} are taken from the triggering operation's {@code tracing}
	 * so the event reflects who/what caused it (e.g. a web user versus an automation); the instance
	 * tags always come from this bounded context. When {@code tracing} is {@code null} (or carries no
	 * actor) the event falls back to the system actor. Does nothing when no listener is registered.
	 * <p>
	 * Never throws: a failing listener is contained, counted and logged (see the class javadoc).
	 */
	public void emit ( BoundedContextEvent event, Tracing tracing ) {
		if ( enabled() ) {
			Tracing kernelTracing = ( tracing == null || tracing.actor() == null )
					? Tracing.kernel(instance)
					: Tracing.init(instance).actor(tracing.actor()).channel(tracing.channel()).command(tracing.command())
							.agent(tracing.agentId(), tracing.agentName());
			EphemeralEvent<BoundedContextEvent> ephemeralEvent =
					kernelTracing.storeOn(EphemeralEvent.of(event, Tags.none()));
			try {
				listener.on(ephemeralEvent);
				noteDelivered();
			} catch ( Exception failure ) {
				noteFailure(event, failure);
			}
		}
	}

	/**
	 * Clears the throttling state after a delivery that worked, and reports the recovery when the
	 * listener had been failing. The volatile read keeps the ordinary path lock-free.
	 */
	private void noteDelivered ( ) {
		if ( consecutiveFailures != 0 ) {
			synchronized ( failureLogLock ) {
				if ( consecutiveFailures != 0 ) {
					LOGGER.warn("bounded context listener {} is delivering again for context '{}', after {} failed emission(s) - those events are lost, nothing replays them",
							listener.getClass().getName(), boundedContext, consecutiveFailures);
					consecutiveFailures = 0;
					suppressedSinceLastLog = 0;
					lastLoggedFailureType = null;
				}
			}
		}
	}

	/**
	 * Counts the failure and logs it, subject to the throttling described on the class. The counter is
	 * incremented before the lock is taken, so the meter keeps the exact rate however much the log is
	 * suppressed.
	 */
	private void noteFailure ( BoundedContextEvent event, Exception failure ) {
		String eventName = event.getClass().getSimpleName();
		failureCounters.computeIfAbsent(eventName, name -> meterRegistry.counter(FAILURE_METER,
				io.micrometer.core.instrument.Tags.of("context", boundedContext, "event", name))).increment();

		synchronized ( failureLogLock ) {
			consecutiveFailures++;
			long now = System.currentTimeMillis();
			boolean firstOfRun = lastLoggedFailureType == null;
			boolean differentCause = !firstOfRun && lastLoggedFailureType != failure.getClass();
			boolean summaryDue = now - lastLoggedAtMs >= FAILURE_SUMMARY_INTERVAL_MS;

			if ( firstOfRun || differentCause || summaryDue ) {
				if ( suppressedSinceLastLog > 0 ) {
					LOGGER.error("bounded context listener {} failed on {} for context '{}' - the event is dropped and the operation that produced it is unaffected. {} further failure(s) were not logged individually, {} in a row now",
							listener.getClass().getName(), eventName, boundedContext, suppressedSinceLastLog, consecutiveFailures, failure);
				} else {
					LOGGER.error("bounded context listener {} failed on {} for context '{}' - the event is dropped and the operation that produced it is unaffected",
							listener.getClass().getName(), eventName, boundedContext, failure);
				}
				lastLoggedFailureType = failure.getClass();
				lastLoggedAtMs = now;
				suppressedSinceLastLog = 0;
			} else {
				suppressedSinceLastLog++;
			}
		}
	}

}
