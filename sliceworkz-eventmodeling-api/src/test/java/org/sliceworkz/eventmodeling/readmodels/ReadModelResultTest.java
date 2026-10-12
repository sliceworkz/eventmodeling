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
package org.sliceworkz.eventmodeling.readmodels;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * {@link ReadModelResult#includes} answers "is my write in this answer" — including for the write that
 * is the newest event the answer reflects, which is the case a strict {@code happenedBefore} gets wrong.
 */
class ReadModelResultTest {

	private static final EventId STORED = EventId.create();

	@Test
	void theWriteThatIsTheNewestEventReflectedIsIncluded ( ) {
		EventReference write = EventReference.of(STORED, 10, 5);
		assertTrue(new ReadModelResult<>("answer", write).includes(write));
	}

	@Test
	void anEarlierWriteIsIncludedAndALaterOneIsNot ( ) {
		ReadModelResult<String> result = new ReadModelResult<>("answer", EventReference.of(STORED, 10, 5));
		assertTrue(result.includes(EventReference.of(EventId.create(), 9, 5)));
		assertFalse(result.includes(EventReference.of(EventId.create(), 11, 5)));
		// a later transaction with a lower position is after it: the order is (tx, position), not position
		assertFalse(result.includes(EventReference.of(EventId.create(), 3, 6)));
	}

	@Test
	void aStoredEventIsIncludedWholeWhicheverOfItsUpcastEventsTheAnswerReachedLast ( ) {
		ReadModelResult<String> result = new ReadModelResult<>("answer", EventReference.of(STORED, 10, 5, 0));
		assertTrue(result.includes(EventReference.of(STORED, 10, 5, 1)));
	}

	@Test
	void anAnswerWithNothingProjectedIncludesNothing ( ) {
		assertFalse(new ReadModelResult<>("answer", null).includes(EventReference.of(STORED, 1, 1)));
	}

	@Test
	void aWriteIsRequired ( ) {
		assertThrows(IllegalArgumentException.class, () -> new ReadModelResult<>("answer", null).includes(null));
	}

}
