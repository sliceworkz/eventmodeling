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
package org.sliceworkz.eventmodeling.module.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * An automation only re-reads its todo list once the projector filling it has passed the last event the
 * automation produced. That comparison used to be on {@code position()} alone, which is a different order
 * from the one the store reads in: position and transaction are assigned independently (a {@code bigserial}
 * and an {@code xid8} on Postgres), so an event can hold a lower position and a higher transaction than one
 * that committed before it. Reporting the projector as caught up while it is not means re-reading a todo
 * list that still holds items the automation has already handled.
 */
public class AutomationCatchUpOrderingTest {

	private static EventReference reference ( long tx, long position ) {
		return EventReference.of(EventId.create(), position, tx);
	}

	@Test
	void hasNotCaughtUpWhenTheProjectorIsBehindOnTransaction ( ) {
		// the projector is ahead on position but behind on transaction, so it has not seen our event yet
		assertFalse(AutomationProcessor.hasCaughtUp(reference(7, 100), Optional.of(reference(9, 42))),
			"a higher position under a lower transaction is not caught up");
	}

	@Test
	void hasCaughtUpWhenTheProjectorIsAheadOnTransaction ( ) {
		assertTrue(AutomationProcessor.hasCaughtUp(reference(9, 42), Optional.of(reference(7, 100))),
			"a lower position under a higher transaction is caught up");
	}

	@Test
	void hasCaughtUpOnTheSameReference ( ) {
		EventReference ours = reference(7, 100);
		assertTrue(AutomationProcessor.hasCaughtUp(ours, Optional.of(ours)),
			"the boundary is inclusive: the event we produced has been projected");
	}

	@Test
	void positionDecidesWithinOneTransaction ( ) {
		assertTrue(AutomationProcessor.hasCaughtUp(reference(7, 100), Optional.of(reference(7, 99))));
		assertFalse(AutomationProcessor.hasCaughtUp(reference(7, 99), Optional.of(reference(7, 100))));
	}

	@Test
	void indexDecidesWithinOneStoredEvent ( ) {
		// an upcasted event yields several references sharing tx and position, ordered by index
		EventReference first = EventReference.of(EventId.create(), 100, 7, 0);
		EventReference second = EventReference.of(EventId.create(), 100, 7, 1);
		assertTrue(AutomationProcessor.hasCaughtUp(second, Optional.of(first)));
		assertFalse(AutomationProcessor.hasCaughtUp(first, Optional.of(second)));
	}

	@Test
	void hasCaughtUpWhenWeHaveProducedNothingYet ( ) {
		assertTrue(AutomationProcessor.hasCaughtUp(reference(7, 100), Optional.empty()),
			"an automation that has produced nothing has nothing to wait for");
	}
}
