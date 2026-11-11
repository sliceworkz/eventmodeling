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
package org.sliceworkz.eventmodeling.examples.localfirst.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class VectorClockTest {

	private static final ReplicaId A = ReplicaId.of("A");
	private static final ReplicaId B = ReplicaId.of("B");

	@Test
	void aClockIncrementedFromAnotherHappenedAfterIt ( ) {
		VectorClock first = VectorClock.empty().increment(A);
		VectorClock second = first.increment(A);

		assertTrue(first.happenedBefore(second));
		assertFalse(second.happenedBefore(first));
		assertFalse(first.concurrentWith(second));
	}

	@Test
	void twoReplicasDecidingWithoutKnowingEachOtherAreConcurrent ( ) {
		VectorClock base = VectorClock.empty().increment(A);
		VectorClock onA = base.increment(A);
		VectorClock onB = base.increment(B);

		assertTrue(onA.concurrentWith(onB));
		assertTrue(onA.happenedBefore(onA.mergedWith(onB).increment(B)));
		assertEquals(new VectorClock(Map.of("A", 2L, "B", 1L)), onA.mergedWith(onB));
	}

	@Test
	void anOperationIsDeliverableOnlyOnceEverythingItWasDecidedOnIsThere ( ) {
		OperationStamp a1 = OperationStamp.next(A, VectorClock.empty());
		OperationStamp b1 = OperationStamp.next(B, a1.clock());     // B decided after seeing A's first
		OperationStamp a2 = OperationStamp.next(A, a1.clock());

		VectorClock empty = VectorClock.empty();
		assertTrue(a1.deliverableAt(empty));
		assertFalse(b1.deliverableAt(empty), "depends on A#1");
		assertFalse(a2.deliverableAt(empty), "A#1 comes first");
		assertTrue(b1.deliverableAt(a1.clock()));
		assertTrue(a1.seenBy(a1.clock()));
		assertEquals("op/B/1", b1.operationId());
	}

}
