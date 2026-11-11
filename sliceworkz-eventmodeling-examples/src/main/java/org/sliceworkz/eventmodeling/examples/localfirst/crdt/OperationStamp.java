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

/**
 * What makes a domain event a replicable operation: the replica that decided it, its sequence number on
 * that replica (a <i>dot</i>), and the vector clock of everything that replica had seen when it decided,
 * this operation included.
 *
 * <p>Two things follow from it. <b>Causal delivery</b>: a replica takes an operation in only once it holds
 * everything the operation was decided on ({@link #deliverableAt}), so an operation on a to-do never
 * arrives before the to-do does. <b>Concurrency detection</b>: two operations whose clocks are not
 * ordered were decided without knowledge of each other, and need a conflict resolution rule.
 */
public record OperationStamp ( ReplicaId origin, long counter, VectorClock clock ) {

	/** The stamp of the next operation a replica decides, having seen {@code seen}. */
	public static OperationStamp next ( ReplicaId origin, VectorClock seen ) {
		VectorClock clock = seen.increment(origin);
		return new OperationStamp(origin, clock.counterOf(origin), clock);
	}

	/** Globally unique, and stable across replicas: the idempotency key the operation is stored under everywhere. */
	public String operationId ( ) {
		return "op/" + origin.value() + "/" + counter;
	}

	/** Whether a replica that has seen {@code local} already holds this operation. */
	public boolean seenBy ( VectorClock local ) {
		return local.counterOf(origin) >= counter;
	}

	/**
	 * Whether a replica that has seen {@code local} can take this operation in now: it is the next one of
	 * its origin, and everything else it was decided on is already there.
	 */
	public boolean deliverableAt ( VectorClock local ) {
		if ( local.counterOf(origin) != counter - 1 ) {
			return false;
		}
		return clock.counters().entrySet().stream()
				.filter(e -> !e.getKey().equals(origin.value()))
				.allMatch(e -> local.counters().getOrDefault(e.getKey(), 0L) >= e.getValue());
	}

	public boolean happenedBefore ( OperationStamp other ) {
		return clock.happenedBefore(other.clock);
	}

	@Override
	public String toString ( ) {
		return origin + "#" + counter + clock;
	}

}
