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

import java.util.Comparator;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MultiValueRegisterTest {

	private static final ReplicaId A = ReplicaId.of("A");
	private static final ReplicaId B = ReplicaId.of("B");

	@Test
	void aLaterWriteReplacesWhatItHasSeen ( ) {
		OperationStamp first = OperationStamp.next(A, VectorClock.empty());
		OperationStamp second = OperationStamp.next(B, first.clock());

		var register = MultiValueRegister.<String>empty().write(first, "x").write(second, "y");

		assertEquals(Set.of("y"), register.values());
		assertFalse(register.concurrentlyWritten());
	}

	@Test
	void concurrentWritesAreAllKeptAndResolvedTheSameWhateverTheOrder ( ) {
		OperationStamp base = OperationStamp.next(A, VectorClock.empty());
		OperationStamp onA = OperationStamp.next(A, base.clock());
		OperationStamp onB = OperationStamp.next(B, base.clock());

		var oneWay = MultiValueRegister.<String>empty().write(base, "x").write(onA, "a").write(onB, "b");
		var otherWay = MultiValueRegister.<String>empty().write(base, "x").write(onB, "b").write(onA, "a");

		assertEquals(Set.of("a", "b"), oneWay.values());
		assertEquals(oneWay.values(), otherWay.values());
		assertTrue(oneWay.concurrentlyWritten());
		assertEquals("b", oneWay.resolved(Comparator.naturalOrder()));
		assertEquals(oneWay.resolved(Comparator.naturalOrder()), otherWay.resolved(Comparator.naturalOrder()));
	}

	@Test
	void writingTheSameOperationTwiceChangesNothing ( ) {
		OperationStamp first = OperationStamp.next(A, VectorClock.empty());
		var register = MultiValueRegister.<String>empty().write(first, "x");

		assertEquals(register.values(), register.write(first, "x").values());
		assertFalse(register.write(first, "x").concurrentlyWritten());
	}

}
