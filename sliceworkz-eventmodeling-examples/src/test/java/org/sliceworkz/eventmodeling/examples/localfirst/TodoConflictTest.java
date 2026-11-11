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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel.ConflictingChange;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel.TodoConflict;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todolist.TodoListReadModel.TodoView;

/**
 * An ongoing to-do, finished by one office and put back on the plan by another while they could not
 * reach each other: a business conflict. It shows on every replica that knows both sides, nothing more
 * can be done with the to-do until a person picks the side that stands, and that choice replicates like
 * any other operation.
 */
class TodoConflictTest extends ReplicaTestSupport {

	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final Network network = Network.peerToPeer(brussels, ghent, antwerp);

	private TodoId coffee;

	@BeforeEach
	void anOngoingTodoTakenInTwoDirectionsDuringAnOutage ( ) {
		coffee = brussels.addTodo("Order coffee");
		brussels.markTodoOngoing(coffee);
		network.synchronize();

		network.isolate(brussels);
		brussels.markTodoDone(coffee);
		ghent.markTodoPlanned(coffee);
		assertTrue(brussels.conflicts().isEmpty(), "while apart, neither office knows of a conflict");
		assertTrue(ghent.conflicts().isEmpty());
	}

	@Test
	void theConflictShowsTheSameOnEveryReplicaOnceTheyHaveSynced ( ) {
		network.healAll();
		network.synchronize();

		TodoConflict expected = new TodoConflict(coffee, "Order coffee", List.of(
				new ConflictingChange(TodoStatus.DONE, brussels.replica(), 3),
				new ConflictingChange(TodoStatus.PLANNED, ghent.replica(), 1)));
		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertEquals(List.of(expected), replica.conflicts(), "on " + replica.replica());
			TodoView view = replica.todo(coffee);
			assertTrue(view.inConflict());
			assertNull(view.status(), "no agreed status until someone resolves it");
			assertEquals(Set.of(TodoStatus.DONE, TodoStatus.PLANNED), view.concurrentStatuses());
		}
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void nothingElseCanBeDoneWithATodoInConflict ( ) {
		network.healAll();
		network.synchronize();

		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertThrows(BusinessException.class, () -> replica.markTodoPlanned(coffee));
			assertThrows(BusinessException.class, () -> replica.markTodoOngoing(coffee));
			assertThrows(BusinessException.class, () -> replica.markTodoDone(coffee));
			assertThrows(BusinessException.class, () -> replica.dropTodo(coffee));
		}
		assertEquals(4, brussels.operations().size(), "the refused actions decided nothing, so nothing was replicated");
	}

	@Test
	void aPersonResolvesItAndTheResolutionReachesEveryone ( ) {
		network.healAll();
		network.synchronize();

		ghent.resolveConflict(coffee, TodoStatus.DONE);       // ghent accepts brussels' version
		assertFalse(ghent.todo(coffee).inConflict(), "settled locally at once");
		assertEquals(1, antwerp.conflicts().size(), "antwerp has not heard yet");

		network.synchronize();

		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertTrue(replica.conflicts().isEmpty());
			assertEquals(TodoStatus.DONE, replica.todo(coffee).status());
		}
		antwerp.markTodoPlanned(coffee);                     // and the to-do can be worked on again
		network.synchronize();
		assertEquals(TodoStatus.PLANNED, brussels.todo(coffee).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aResolutionPicksOneOfTheConflictingSides ( ) {
		network.healAll();
		network.synchronize();

		assertThrows(BusinessException.class, () -> ghent.resolveConflict(coffee, TodoStatus.ONGOING), "neither office chose ongoing");
		TodoId paper = ghent.addTodo("Buy paper");
		assertThrows(BusinessException.class, () -> ghent.resolveConflict(paper, TodoStatus.PLANNED), "no conflict to resolve");
	}

	@Test
	void twoOfficesResolvingTheSameWayWhileApartSettleIt ( ) {
		network.healAll();
		network.synchronize();
		network.isolate(antwerp);

		ghent.resolveConflict(coffee, TodoStatus.PLANNED);
		antwerp.resolveConflict(coffee, TodoStatus.PLANNED);
		network.healAll();
		network.synchronize();

		assertTrue(brussels.conflicts().isEmpty(), "two concurrent resolutions agreeing are no conflict");
		assertEquals(TodoStatus.PLANNED, brussels.todo(coffee).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void twoOfficesResolvingDifferentlyWhileApartConflictAgain ( ) {
		network.healAll();
		network.synchronize();
		network.isolate(antwerp);

		ghent.resolveConflict(coffee, TodoStatus.PLANNED);
		antwerp.resolveConflict(coffee, TodoStatus.DONE);
		network.healAll();
		network.synchronize();

		TodoConflict again = brussels.conflicts().get(0);
		assertEquals(List.of(antwerp.replica(), ghent.replica()), again.changes().stream().map(ConflictingChange::decidedBy).toList(),
				"the conflict is now between the two resolutions");

		brussels.resolveConflict(coffee, TodoStatus.DONE);
		network.synchronize();
		assertTrue(antwerp.conflicts().isEmpty());
		assertEquals(TodoStatus.DONE, ghent.todo(coffee).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aConflictOnlyHoldsBackTheOfficesThatKnowOfIt ( ) {
		network.healAll();
		network.isolate(antwerp);
		network.synchronize();
		assertEquals(1, ghent.conflicts().size());

		antwerp.markTodoDone(coffee);           // antwerp has not heard of either side, and goes ahead
		network.healAll();
		network.synchronize();

		assertEquals(Set.of(TodoStatus.DONE, TodoStatus.PLANNED), brussels.todo(coffee).concurrentStatuses(),
				"antwerp's change joins brussels' side; ghent's is still against it");
		assertEquals(3, brussels.conflicts().get(0).changes().size());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aDropMadeWithoutKnowingOfTheConflictSettlesIt ( ) {
		network.healAll();
		network.isolate(antwerp);
		network.synchronize();

		antwerp.dropTodo(coffee);
		network.healAll();
		network.synchronize();

		assertTrue(brussels.conflicts().isEmpty(), "drop wins, whatever else happened to it");
		assertTrue(brussels.todos().isEmpty());
		assertConverged(brussels, ghent, antwerp);
	}

}
