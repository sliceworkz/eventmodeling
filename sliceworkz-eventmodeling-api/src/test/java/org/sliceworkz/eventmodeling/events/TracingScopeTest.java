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
package org.sliceworkz.eventmodeling.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class TracingScopeTest {

	@AfterEach
	void nothingLeftBound ( ) {
		assertEquals(Optional.empty(), TracingScope.current(), "a test left a tracing bound to its thread");
	}

	@Test
	void aBoundTracingIsCurrentUntilItsScopeCloses ( ) {
		Tracing request = Tracing.actorAndChannel("alice", "api");
		try ( TracingScope scope = TracingScope.bind(request) ) {
			assertEquals(Optional.of(request), TracingScope.current());
		}
		assertTrue(TracingScope.current().isEmpty());
	}

	@Test
	void scopesNestAndRestoreWhatWasBoundBefore ( ) {
		Tracing outer = Tracing.actorAndChannel("alice", "api");
		Tracing inner = Tracing.actorAndChannel("bob", "mcp");
		try ( TracingScope o = TracingScope.bind(outer) ) {
			try ( TracingScope i = TracingScope.bind(inner) ) {
				assertEquals(Optional.of(inner), TracingScope.current());
			}
			assertEquals(Optional.of(outer), TracingScope.current());
		}
	}

	@Test
	void closingTwiceRestoresOnce ( ) {
		Tracing outer = Tracing.actorAndChannel("alice", "api");
		try ( TracingScope o = TracingScope.bind(outer) ) {
			TracingScope inner = TracingScope.bind(Tracing.actorAndChannel("bob", "mcp"));
			inner.close();
			TracingScope later = TracingScope.bind(Tracing.actorAndChannel("carol", "api"));
			inner.close();
			assertEquals("carol", TracingScope.current().orElseThrow().actor(), "a second close must not undo a later binding");
			later.close();
			assertEquals(Optional.of(outer), TracingScope.current());
		}
	}

	@Test
	void aBindingBelongsToItsThread ( ) throws InterruptedException {
		AtomicReference<Optional<Tracing>> seenElsewhere = new AtomicReference<>();
		try ( TracingScope scope = TracingScope.bind(Tracing.actorAndChannel("alice", "api")) ) {
			Thread other = Thread.ofVirtual().start(( ) -> {
				seenElsewhere.set(TracingScope.current());
				scope.close(); // not this thread's to restore
			});
			other.join();
			assertEquals(Optional.empty(), seenElsewhere.get(), "nothing is inherited by another thread");
			assertEquals("alice", TracingScope.current().orElseThrow().actor(), "a close on another thread does nothing");
		}
	}

	@Test
	void aNullTracingIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, ( ) -> TracingScope.bind(null));
	}

}
