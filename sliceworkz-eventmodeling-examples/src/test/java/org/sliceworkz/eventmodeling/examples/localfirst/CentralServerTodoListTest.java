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

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;

/**
 * A central server with offices around it, each linked to the centre only. The centre is a replica like
 * any other: it does not decide for the offices, it relays. The vector clocks travel with the operations,
 * so an office learns what another decided, and what that was based on, without ever talking to it.
 */
class CentralServerTodoListTest extends ReplicaTestSupport {

	private final TodoListServer central = replica("central");
	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final Network network = Network.star(central, brussels, ghent, antwerp);

	@Test
	void officesThatNeverTalkToEachOtherConvergeThroughTheCentre ( ) {
		assertThrows(IllegalArgumentException.class, () -> network.disconnect(brussels, ghent), "there is no direct link");

		TodoId coffee = brussels.addTodo("Order coffee");
		network.sync(central, brussels);
		network.sync(central, ghent);
		ghent.markTodoOngoing(coffee);
		network.sync(central, ghent);
		network.sync(central, antwerp);

		assertEquals(TodoStatus.ONGOING, antwerp.todo(coffee).status());
		assertEquals(TodoStatus.PLANNED, brussels.todo(coffee).status(), "brussels has not synced since");

		network.synchronize();
		assertConverged(central, brussels, ghent, antwerp);
	}

	@Test
	void theOfficesKeepWorkingWhileTheCentreIsDown ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();

		network.isolate(central);
		brussels.markTodoOngoing(coffee);
		brussels.markTodoDone(coffee);
		TodoId paper = ghent.addTodo("Buy paper");
		antwerp.dropTodo(coffee);
		network.synchronize();

		assertEquals(TodoStatus.DONE, brussels.todo(coffee).status(), "each office sees its own work");
		assertFalse(antwerp.todos().stream().anyMatch(t -> t.todoId().equals(paper)), "no way to hear of ghent's add");

		network.rejoin(central);
		network.synchronize();

		assertEquals(TodoStatus.DROPPED, brussels.todo(coffee).status(), "antwerp's drop was concurrent with brussels' work, and drop wins");
		assertEquals(TodoStatus.PLANNED, antwerp.todo(paper).status());
		assertConverged(central, brussels, ghent, antwerp);
	}

	@Test
	void anOfficeThatWasAwayLongTakesInEverythingItMissedInOneExchange ( ) {
		network.isolate(antwerp);
		for ( int i = 1; i <= 20; i++ ) {
			TodoListServer office = i % 2 == 0 ? brussels : ghent;
			TodoId todo = office.addTodo("Task " + i);
			office.markTodoOngoing(todo);
			network.synchronize();
		}

		network.rejoin(antwerp);
		assertEquals(40, network.sync(central, antwerp), "the centre sends everything, in an order antwerp can take in");
		assertEquals(0, antwerp.waitingOperations());
		network.synchronize();
		assertConverged(central, brussels, ghent, antwerp);
	}

}
