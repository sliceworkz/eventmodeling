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
package org.sliceworkz.eventmodeling.observability;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;

/**
 * What a bounded context reports about the work it does, for an application to turn into meters, spans,
 * log lines or anything else — the framework names no metrics or tracing library itself.
 * <p>
 * Configured on the builder with {@code .observer(o)}; {@link #NOOP} is the default, so observation is
 * opt-in. The event store the context builds reports its own operations to an
 * {@link org.sliceworkz.eventstore.observability.EventStoreObserver}, a separate SPI configured on the
 * storage or with the builder's {@code .eventStoreObserver(o)}: this one reports what the framework adds
 * on top — commands, read models, aggregates, snapshots, automations, translators and dispatchers.
 * <p>
 * <b>An operation is a scope.</b> {@link #start(Observation)} is called on the caller's thread before the
 * operation does anything, and the framework completes or fails the returned {@link Observation.Scope}
 * exactly once and closes it in a {@code finally}, all on that thread — so an observer may make a span
 * current between {@code start} and {@code close}, and what the operation does inside it (the event store's
 * own observations among them) nests beneath it with nothing propagated. The duration is the observer's to
 * measure, between the two calls.
 * <p>
 * <b>A completion is an answer, not only a success.</b> A command conflicting on its consistency boundary
 * and a command rejected by a business rule complete, with {@link Outcome.Conflicted} and
 * {@link Outcome.Rejected}; {@link Observation.Scope#failed(Throwable)} is kept for an operation that could
 * not answer — the same split {@code CommandFailed} and {@code CommandRejected} make on the
 * {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener}.
 * <p>
 * <b>Not a {@code BoundedContextListener}.</b> A listener receives {@link BoundedContextEvent}s: facts about
 * the context's lifecycle and the outcomes of its work, one record per outcome, meant to be stored and read
 * back. An observer is told where each operation starts and ends, per event where the framework works per
 * event, and keeps nothing: it is the seam for metrics and tracing, which is why it is called on the hot
 * path and why a slow one is a slow context.
 * <p>
 * <b>An observer never fails an operation.</b> The framework wraps every observer with
 * {@link #contained(BoundedContextObserver)}: what it throws is caught and logged, never reaching the work
 * it observes.
 * <p>
 * <b>Cardinality is the observer's concern.</b> The observations carry names — of commands, read models,
 * event types — which are bounded by the code, and {@link org.sliceworkz.eventmodeling.events.Tracing}s,
 * aggregate identities and snapshot keys, which are data. An observer turning the latter into a metrics tag
 * must bound the values it admits, since a registry never evicts a meter; a tracer wants them whole.
 */
public interface BoundedContextObserver {

	/**
	 * The observer that observes nothing: every scope it returns is one shared instance, and nothing is
	 * allocated or recorded. The default wherever no observer is given.
	 */
	BoundedContextObserver NOOP = NoopObserver.INSTANCE;

	/**
	 * An operation starts. Called synchronously on the caller's thread, before the operation does
	 * anything; the returned scope is current on that thread until it is closed.
	 *
	 * @param <O> what the operation reports when it completes
	 * @param observation what is about to be done
	 * @return the scope of this one operation, never null
	 */
	<O extends Outcome> Observation.Scope<O> start ( Observation<O> observation );

	/**
	 * The bounded context's {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener}
	 * threw on an event. The failure has been contained — the operation that produced the event carried on,
	 * and the event is lost to the listener — and is logged with throttling, so this is where the true rate
	 * is kept: called for every failed delivery, never throttled.
	 *
	 * @param boundedContext the name of the bounded context
	 * @param event the event the listener failed on
	 * @param failure what the listener threw
	 */
	default void listenerFailed ( String boundedContext, BoundedContextEvent event, Exception failure ) { }

	/**
	 * Wraps an observer so that nothing it throws reaches the operation it observes.
	 * <p>
	 * The framework applies this to every observer it is given; an implementation has no need to call it.
	 * A throwable escaping the observer — a {@code RuntimeException}, or a {@code LinkageError} from a
	 * binding whose library is missing — is caught and logged, at ERROR the first time for that observer
	 * with its stack trace and at DEBUG afterwards, so a broken observer is reported without every operation
	 * of the context writing a stack trace. A scope whose {@code start} threw is replaced by a no-op one.
	 * {@link #NOOP} is returned as it is, and so is an observer that is already contained.
	 *
	 * @param observer the observer to contain; must not be null
	 * @return an observer that never throws
	 * @throws IllegalArgumentException if the observer is null
	 */
	static BoundedContextObserver contained ( BoundedContextObserver observer ) {
		if ( observer == null ) {
			throw new IllegalArgumentException("observer cannot be null.  Use BoundedContextObserver.NOOP to observe nothing");
		}
		if ( observer == NOOP || observer instanceof ContainedObserver ) {
			return observer;
		}
		return new ContainedObserver(observer);
	}

}
