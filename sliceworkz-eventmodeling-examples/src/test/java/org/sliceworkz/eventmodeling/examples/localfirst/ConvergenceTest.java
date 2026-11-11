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
package org.sliceworkz.eventmodeling.examples.localfirst;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;

/**
 * The property itself: whatever the users do, wherever the network is cut, and in whatever order and
 * however often operations arrive, replicas that received the same operations are in the same state.
 */
class ConvergenceTest extends ReplicaTestSupport {

	private static final int RUNS = 25;
	private static final int STEPS = 60;

	@Test
	void randomWorkUnderRandomPartitionsAlwaysConverges ( ) {
		for ( long seed = 1; seed <= RUNS; seed++ ) {
			Random random = new Random(seed);
			TodoListServer[] replicas = { replica("r" + seed + "a"), replica("r" + seed + "b"), replica("r" + seed + "c") };
			Network network = random.nextBoolean()
					? Network.peerToPeer(replicas)
					: Network.star(replicas[0], replicas[1], replicas[2]);

			for ( int step = 0; step < STEPS; step++ ) {
				TodoListServer replica = replicas[random.nextInt(replicas.length)];
				switch ( random.nextInt(10) ) {
					case 0 -> network.isolate(replica);
					case 1 -> network.rejoin(replica);
					case 2 -> network.synchronize();
					case 3 -> network.sync(replicas[0], replicas[1 + random.nextInt(2)]);
					default -> work(replica, random);
				}
			}

			network.healAll();
			network.synchronize();
			assertConverged(replicas);
		}
	}

	@Test
	void theOrderOperationsArriveInDoesNotMatter ( ) {
		Random random = new Random(42);
		TodoListServer[] writers = { replica("w1"), replica("w2"), replica("w3") };
		Network network = Network.peerToPeer(writers);
		for ( int step = 0; step < 80; step++ ) {
			if ( random.nextInt(6) == 0 ) {
				network.sync(writers[random.nextInt(3)], writers[random.nextInt(3)]);
			} else {
				work(writers[random.nextInt(3)], random);
			}
		}
		network.synchronize();
		List<TodoEvent> everything = writers[0].operations();

		for ( int run = 0; run < 10; run++ ) {
			TodoListServer reader = replica("reader" + run);
			List<TodoEvent> shuffled = new ArrayList<>(everything);
			shuffled.addAll(everything.subList(0, everything.size() / 3));      // some arrive twice
			Collections.shuffle(shuffled, random);
			for ( int from = 0; from < shuffled.size(); from += 7 ) {           // in small, unordered batches
				reader.receive(shuffled.subList(from, Math.min(from + 7, shuffled.size())));
			}
			assertEquals(writers[0].state(), reader.state(), "the list after delivery order " + run);
			assertConverged(writers[0], reader);
		}
	}

	/** One user action on a replica; one its local rules reject is simply not done. */
	private static void work ( TodoListServer replica, Random random ) {
		List<TodoId> known = replica.state().stream().map(t -> t.todoId()).toList();
		try {
			if ( known.isEmpty() || random.nextInt(4) == 0 ) {
				replica.addTodo("Task " + random.nextInt(12));
				return;
			}
			var conflicts = replica.conflicts();
			if ( !conflicts.isEmpty() && random.nextInt(3) == 0 ) {
				var conflict = conflicts.get(random.nextInt(conflicts.size()));
				var sides = conflict.changes();
				replica.resolveConflict(conflict.todoId(), sides.get(random.nextInt(sides.size())).status());
				return;
			}
			TodoId todo = known.get(random.nextInt(known.size()));
			switch ( random.nextInt(4) ) {
				case 0 -> replica.markTodoPlanned(todo);
				case 1 -> replica.markTodoOngoing(todo);
				case 2 -> replica.markTodoDone(todo);
				default -> {
					if ( random.nextInt(3) == 0 ) {
						replica.dropTodo(todo);
					} else {
						replica.markTodoOngoing(todo);
					}
				}
			}
		} catch ( BusinessException rejectedLocally ) {
			// the user tried something the rules do not allow here; nothing was decided, nothing to replicate
		}
	}

}
