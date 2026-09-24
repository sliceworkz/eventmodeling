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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Whatever an observer throws never reaches the operation it observes: {@link BoundedContextObserver#contained}
 * catches it on every call, including a {@code LinkageError} from a binding whose library is missing.
 */
class ContainedObserverTest {

	private static final Observation.CommandExecution EXECUTION =
			new Observation.CommandExecution("context", "Command", Object.class, Observation.Target.DOMAIN, null);

	@Test
	void theNoopObserverAndAContainedOneAreReturnedAsTheyAre ( ) {
		assertSame(BoundedContextObserver.NOOP, BoundedContextObserver.contained(BoundedContextObserver.NOOP));
		BoundedContextObserver contained = BoundedContextObserver.contained(new ThrowingScopes());
		assertSame(contained, BoundedContextObserver.contained(contained));
	}

	@Test
	void aNullObserverIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> BoundedContextObserver.contained(null));
	}

	@Test
	void aThrowingStartIsReplacedByANoopScope ( ) {
		BoundedContextObserver contained = BoundedContextObserver.contained(new BoundedContextObserver() {
			@Override
			public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
				throw new NoClassDefFoundError("io/missing/Library");
			}
		});

		Observation.Scope<Outcome.CommandOutcome> scope = assertDoesNotThrow(() -> contained.start(EXECUTION));
		assertNotNull(scope);
		assertDoesNotThrow(() -> {
			scope.completed(new Outcome.Rejected("no"));
			scope.close();
		});
	}

	@Test
	void aNullScopeIsReplacedByANoopScope ( ) {
		BoundedContextObserver contained = BoundedContextObserver.contained(new BoundedContextObserver() {
			@Override
			public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
				return null;
			}
		});

		assertNotNull(contained.start(EXECUTION));
	}

	@Test
	void throwingScopeCallsAndListenerFailureReportsAreContained ( ) {
		BoundedContextObserver contained = BoundedContextObserver.contained(new ThrowingScopes());

		Observation.Scope<Outcome.CommandOutcome> scope = contained.start(EXECUTION);
		assertDoesNotThrow(() -> scope.completed(new Outcome.Rejected("no")));
		assertDoesNotThrow(() -> scope.failed(new IllegalStateException()));
		assertDoesNotThrow(scope::close);
		assertDoesNotThrow(() -> contained.listenerFailed("context", null, new IllegalStateException()));
	}

	/** An observer whose every scope call throws. */
	private static final class ThrowingScopes implements BoundedContextObserver {

		@Override
		public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
			return new Observation.Scope<>() {
				@Override
				public void completed ( O outcome ) {
					throw new IllegalStateException("completed");
				}

				@Override
				public void failed ( Throwable failure ) {
					throw new IllegalStateException("failed");
				}

				@Override
				public void close ( ) {
					throw new IllegalStateException("close");
				}
			};
		}

		@Override
		public void listenerFailed ( String boundedContext, org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent event, Exception failure ) {
			throw new IllegalStateException("listenerFailed");
		}

	}

}
