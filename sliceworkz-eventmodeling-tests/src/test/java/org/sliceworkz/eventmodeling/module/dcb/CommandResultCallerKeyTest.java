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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.CommandResult.CallerKey;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins the one way a command says how its execution is keyed: every combination of a command's own key
 * and a caller's, and the two that make no sense refused when the command says them.
 */
class CommandResultCallerKeyTest {

	private CommandResultImpl<Object, Object> result ( ) {
		return new CommandResultImpl<>("test", EventStreamId.forContext("test"), Tracing.actorAndChannel("tester", "test"), EventFilter.matchNone(), null);
	}

	@Test
	void eachChoiceResolvesTheKeyAsItsTableSays ( ) {
		assertEquals("caller", result().resolveIdempotencyKey("caller"));
		assertEquals(null, result().resolveIdempotencyKey(null));

		assertEquals("caller", result().idempotencyKey(CallerKey.REQUIRED).resolveIdempotencyKey("caller"));
		assertThrows(IllegalStateException.class, () -> result().idempotencyKey(CallerKey.REQUIRED).resolveIdempotencyKey(null));

		assertEquals(null, result().idempotencyKey(CallerKey.REFUSED).resolveIdempotencyKey(null));
		assertThrows(IllegalStateException.class, () -> result().idempotencyKey(CallerKey.REFUSED).resolveIdempotencyKey("caller"));

		assertEquals("caller", result().idempotencyKey("own", CallerKey.PREFERRED).resolveIdempotencyKey("caller"));
		assertEquals("own", result().idempotencyKey("own", CallerKey.PREFERRED).resolveIdempotencyKey(null));

		assertEquals("own", result().idempotencyKey("own", CallerKey.IGNORED).resolveIdempotencyKey("caller"));
		assertEquals("own", result().idempotencyKey("own", CallerKey.IGNORED).resolveIdempotencyKey(null));

		assertEquals("own", result().idempotencyKey("own", CallerKey.REFUSED).resolveIdempotencyKey(null));
		assertThrows(IllegalStateException.class, () -> result().idempotencyKey("own", CallerKey.REFUSED).resolveIdempotencyKey("caller"));
	}

	@Test
	void aRequiredCallerKeyLeavesNoUseForTheCommandsOwn ( ) {
		assertThrows(IllegalArgumentException.class, () -> result().idempotencyKey("own", CallerKey.REQUIRED));
	}

	@Test
	void weighingACallerKeyNeedsAKeyOfTheCommandsOwn ( ) {
		assertThrows(IllegalArgumentException.class, () -> result().idempotencyKey(CallerKey.PREFERRED));
		assertThrows(IllegalArgumentException.class, () -> result().idempotencyKey(CallerKey.IGNORED));
		assertThrows(IllegalArgumentException.class, () -> result().idempotencyKey(" ", CallerKey.IGNORED));
	}

}
