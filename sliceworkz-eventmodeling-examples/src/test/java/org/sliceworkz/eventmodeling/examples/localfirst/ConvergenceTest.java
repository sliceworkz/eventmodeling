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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.Verdict;

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
	void randomWorkWithAnOfficeTakenOverPartwayConverges ( ) {
		int setAside = 0;
		int reviewed = 0;
		for ( long seed = 1; seed <= RUNS; seed++ ) {
			Random random = new Random(seed);
			TodoListServer[] offices = { replica("x" + seed + "a"), replica("x" + seed + "b"), replica("x" + seed + "c") };
			TodoListServer security = replica(TodoListDomain.SECURITY_OFFICE.value());
			TodoListServer compromised = offices[1];
			Network network = Network.peerToPeer(offices[0], offices[1], offices[2], security);
			long takenOverAfter = -1;

			int steps = 3 * STEPS;
			for ( int step = 0; step < steps; step++ ) {
				if ( step == steps / 3 ) {
					takenOverAfter = compromised.versionVector().counterOf(compromised.replica());
				}
				if ( step == 2 * steps / 3 ) {
					security.distrustReplica(compromised.replica(), takenOverAfter, "taken over at step " + steps / 3);
				}
				TodoListServer office = offices[random.nextInt(offices.length)];
				switch ( random.nextInt(10) ) {
					case 0 -> network.isolate(office);
					case 1 -> network.rejoin(office);
					case 2, 3 -> network.synchronize();
					case 4, 5 -> review(office, random);
					default -> {
						work(compromised, random);       // the office that will turn is busy, before and after
						work(office, random);
					}
				}
			}

			network.healAll();
			network.synchronize();
			assertConverged(offices[0], offices[1], offices[2], security);
			setAside += security.setAside().size();
			reviewed += (int) security.operations().stream().filter(op -> op instanceof TodoListDomain.TodoEvent.TodoChangeReviewed).count();
		}
		assertTrue(setAside > 0 && reviewed > 0, "the runs set operations aside (" + setAside + ") and reviewed changes (" + reviewed + ")");
	}

	@Test
	void randomWorkWithOfficesJoiningRetiringAndBeingPrunedConverges ( ) {
		int pruned = 0;
		for ( long seed = 1; seed <= RUNS; seed++ ) {
			Random random = new Random(seed);
			List<TodoListServer> offices = new ArrayList<>(List.of(replica("m" + seed + "a"), replica("m" + seed + "b"), replica("m" + seed + "c")));
			Network network = Network.peerToPeer(offices.toArray(TodoListServer[]::new));
			TodoListServer retiring = offices.get(2);

			for ( int step = 0; step < 2 * STEPS; step++ ) {
				if ( step == STEPS / 2 ) {
					retiring.retire();
				}
				if ( step == STEPS ) {
					TodoListServer sponsor = offices.get(random.nextInt(2));
					TodoListServer newcomer = joining("m" + seed + "d", sponsor);
					network.join(newcomer, offices.toArray(TodoListServer[]::new));
					offices.add(newcomer);
				}
				TodoListServer office = offices.get(random.nextInt(offices.size()));
				switch ( random.nextInt(10) ) {
					case 0 -> network.isolate(office);
					case 1 -> network.rejoin(office);
					case 2, 3 -> network.synchronize();
					case 4 -> office.pruneRetiredOffices();
					default -> work(office, random);
				}
			}

			network.healAll();
			network.synchronize();
			assertConverged(offices.toArray(TodoListServer[]::new));
			pruned += (int) offices.get(0).operations().stream().filter(op -> op instanceof TodoListDomain.TodoEvent.RetiredOfficePruned).count();
		}
		assertTrue(pruned > 0, "the runs pruned a retired office (" + pruned + ")");
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

	/** A reviewer on this office settles one change waiting for review, if there is one. */
	private static void review ( TodoListServer office, Random random ) {
		var pending = office.pendingReviews();
		if ( !pending.isEmpty() ) {
			var change = pending.get(random.nextInt(pending.size()));
			office.reviewChange(change.todoId(), change.operationId(), random.nextBoolean() ? Verdict.CONFIRMED : Verdict.REVERTED);
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
				var sides = conflict.renames();
				replica.settleTitle(conflict.todoId(), random.nextBoolean()
						? sides.get(random.nextInt(sides.size())).title()
						: "Settled " + random.nextInt(12));
				return;
			}
			TodoId todo = known.get(random.nextInt(known.size()));
			switch ( random.nextInt(5) ) {
				case 4 -> replica.renameTodo(todo, "Renamed " + random.nextInt(12));
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
