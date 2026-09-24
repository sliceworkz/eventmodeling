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

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;

/**
 * What {@link BoundedContextObserver#contained(BoundedContextObserver)} returns: the observer, with nothing
 * it throws reaching the operation it observes.
 */
final class ContainedObserver implements BoundedContextObserver {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContainedObserver.class);

	private final BoundedContextObserver delegate;

	/**
	 * Whether this observer has already reported a failure at ERROR. Logged loudly once, since a broken
	 * observer is something to fix; quietly afterwards, since it fails on every operation of the context.
	 */
	private final AtomicBoolean failureReported = new AtomicBoolean();

	ContainedObserver ( BoundedContextObserver delegate ) {
		this.delegate = delegate;
	}

	@Override
	public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
		try {
			Observation.Scope<O> scope = delegate.start(observation);
			return scope == null ? NoopObserver.scope() : new ContainedScope<>(scope);
		} catch ( RuntimeException | LinkageError e ) {
			report("start", e);
			return NoopObserver.scope();
		}
	}

	@Override
	public void listenerFailed ( String boundedContext, BoundedContextEvent event, Exception failure ) {
		contain("listenerFailed", () -> delegate.listenerFailed(boundedContext, event, failure));
	}

	private void contain ( String method, Runnable call ) {
		try {
			call.run();
		} catch ( RuntimeException | LinkageError e ) {
			report(method, e);
		}
	}

	private void report ( String method, Throwable e ) {
		if ( failureReported.compareAndSet(false, true) ) {
			LOGGER.error("bounded context observer {} threw from {}; the operation it observed is unaffected, and further failures of this observer are logged at DEBUG: {}",
					delegate.getClass().getName(), method, e.getMessage(), e);
		} else {
			LOGGER.debug("bounded context observer {} threw from {}: {}", delegate.getClass().getName(), method, e.getMessage(), e);
		}
	}

	@Override
	public String toString ( ) {
		return "contained " + delegate;
	}

	private final class ContainedScope<O extends Outcome> implements Observation.Scope<O> {

		private final Observation.Scope<O> scope;

		private ContainedScope ( Observation.Scope<O> scope ) {
			this.scope = scope;
		}

		@Override
		public void completed ( O outcome ) {
			contain("completed", () -> scope.completed(outcome));
		}

		@Override
		public void failed ( Throwable failure ) {
			contain("failed", () -> scope.failed(failure));
		}

		@Override
		public void close ( ) {
			contain("close", scope::close);
		}

	}

}
