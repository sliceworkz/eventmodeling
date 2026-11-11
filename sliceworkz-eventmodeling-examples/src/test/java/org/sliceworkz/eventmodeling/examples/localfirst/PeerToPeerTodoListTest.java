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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todolist.TodoListReadModel.TodoView;

/** Three offices, each running a replica, linked to each other directly. */
class PeerToPeerTodoListTest extends ReplicaTestSupport {

	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final Network network = Network.peerToPeer(brussels, ghent, antwerp);

	@Test
	void aChangeIsVisibleLocallyAtOnceAndEverywhereAfterSync ( ) {
		TodoId todo = brussels.addTodo("Order coffee");

		assertEquals(List.of(new TodoView(todo, "Order coffee", TodoStatus.PLANNED, Set.of(TodoStatus.PLANNED))), brussels.todos());
		assertTrue(ghent.todos().isEmpty(), "nothing is sent until the replicas sync");

		network.synchronize();

		assertEquals(TodoStatus.PLANNED, ghent.todo(todo).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void theLifecycleRulesAreCheckedLocallyWithoutAskingAnyone ( ) {
		network.isolate(brussels);
		TodoId todo = brussels.addTodo("Order coffee");

		assertThrows(BusinessException.class, () -> brussels.markTodoDone(todo), "done only after ongoing");
		brussels.markTodoOngoing(todo);
		assertThrows(BusinessException.class, () -> brussels.markTodoOngoing(todo), "already ongoing");
		brussels.markTodoDone(todo);
		brussels.dropTodo(todo);
		assertThrows(BusinessException.class, () -> brussels.markTodoPlanned(todo), "dropped");
		assertThrows(BusinessException.class, () -> ghent.markTodoOngoing(todo), "ghent has not heard of it yet");
	}

	@Test
	void everyOfficeKeepsWorkingWhenItsConnectionIsDownAndCatchesUpOnceItIsBack ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();

		network.isolate(antwerp);
		ghent.markTodoOngoing(coffee);
		TodoId paper = brussels.addTodo("Buy paper");
		TodoId keys = antwerp.addTodo("Copy keys");
		antwerp.markTodoOngoing(keys);
		network.synchronize();

		assertEquals(TodoStatus.ONGOING, brussels.todo(coffee).status());
		assertEquals(TodoStatus.PLANNED, antwerp.todo(coffee).status(), "antwerp is cut off, so its view is stale, but usable");
		assertEquals(2, antwerp.todos().size());
		assertConverged(brussels, ghent);

		network.rejoin(antwerp);
		network.synchronize();

		assertEquals(3, ghent.todos().size());
		assertEquals(TodoStatus.ONGOING, antwerp.todo(coffee).status());
		assertEquals(TodoStatus.ONGOING, brussels.todo(keys).status());
		assertEquals(TodoStatus.PLANNED, antwerp.todo(paper).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void concurrentStatusChangesAreKeptAndResolvedTheSameOnEveryReplica ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		brussels.markTodoOngoing(coffee);
		network.synchronize();

		network.disconnect(brussels, ghent);
		network.isolate(antwerp);
		brussels.markTodoDone(coffee);     // brussels finishes it ...
		ghent.markTodoPlanned(coffee);     // ... while ghent, not knowing, puts it back on the plan

		network.healAll();
		network.synchronize();

		TodoView seen = antwerp.todo(coffee);
		assertEquals(TodoStatus.DONE, seen.status(), "the status that got furthest wins");
		assertEquals(Set.of(TodoStatus.DONE, TodoStatus.PLANNED), seen.concurrentStatuses());
		assertTrue(seen.inConflict());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aChangeMadeAfterSeeingAConflictSettlesIt ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		brussels.markTodoOngoing(coffee);
		network.synchronize();
		network.isolate(brussels);
		brussels.markTodoDone(coffee);
		ghent.markTodoPlanned(coffee);
		network.healAll();
		network.synchronize();

		ghent.markTodoPlanned(coffee);     // ghent sees DONE, and reopens it deliberately
		network.synchronize();

		TodoView settled = brussels.todo(coffee);
		assertEquals(TodoStatus.PLANNED, settled.status());
		assertFalse(settled.inConflict(), "the reopen saw both concurrent writes, so it replaces them");
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aDropWinsOverAConcurrentChange ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();
		network.isolate(ghent);
		ghent.markTodoOngoing(coffee);
		antwerp.dropTodo(coffee);
		network.healAll();
		network.synchronize();

		assertTrue(brussels.todos().isEmpty());
		assertEquals(Set.of(TodoStatus.ONGOING, TodoStatus.DROPPED), brussels.todo(coffee).concurrentStatuses());
		assertThrows(BusinessException.class, () -> ghent.markTodoDone(coffee), "dropped, as far as ghent now knows");
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aRuleOverSeveralOperationsHoldsLocallyButNotAcrossAPartition ( ) {
		brussels.addTodo("Order coffee");
		assertThrows(BusinessException.class, () -> brussels.addTodo("Order coffee"), "unique, locally");

		network.isolate(antwerp);
		ghent.addTodo("Book the room");
		antwerp.addTodo("Book the room");
		network.healAll();
		network.synchronize();

		assertEquals(2, brussels.todos().stream().filter(t -> t.title().equals("Book the room")).count(),
				"each replica's add was valid where it was decided; without coordination, both stand");
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void anOperationWaitsUntilWhatItWasDecidedOnHasArrived ( ) {
		network.isolate(antwerp);
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();
		ghent.markTodoOngoing(coffee);

		var ghentsOperation = ghent.operationsMissingAt(brussels.versionVector());
		assertEquals(0, antwerp.receive(ghentsOperation), "the to-do it is about has not reached antwerp");
		assertEquals(1, antwerp.waitingOperations());
		assertTrue(antwerp.todos().isEmpty());

		var brusselsOperation = brussels.operationsMissingAt(antwerp.versionVector());
		assertEquals(2, antwerp.receive(brusselsOperation), "the add, and then the change waiting for it");
		assertEquals(TodoStatus.ONGOING, antwerp.todo(coffee).status());
		assertEquals(0, antwerp.waitingOperations());
	}

	@Test
	void anOperationDeliveredTwiceIsTakenInOnce ( ) {
		brussels.addTodo("Order coffee");
		var operations = brussels.operationsMissingAt(ghent.versionVector());

		assertEquals(1, ghent.receive(operations));
		assertEquals(0, ghent.receive(operations));
		assertEquals(0, brussels.receive(ghent.operations()), "its own operation, sent back");
		assertEquals(1, ghent.operations().size());
		assertConverged(brussels, ghent);
	}

}
