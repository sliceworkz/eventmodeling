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
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel.ConflictingRename;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel.TodoConflict;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todolist.TodoListReadModel.TodoView;

/**
 * A to-do renamed by two offices, differently, while they could not reach each other: a conflict no rule
 * can settle. It shows on every replica that knows both renames, nothing more can be done with the to-do
 * until a person settles its title, and that settlement replicates like any other operation.
 */
class TodoConflictTest extends ReplicaTestSupport {

	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final Network network = Network.peerToPeer(brussels, ghent, antwerp);

	private TodoId paper;

	@BeforeEach
	void aTodoRenamedDifferentlyByTwoOfficesDuringAnOutage ( ) {
		paper = brussels.addTodo("Buy paper");
		network.synchronize();

		network.isolate(brussels);
		brussels.renameTodo(paper, "Buy printer paper");
		ghent.renameTodo(paper, "Buy A4 paper");
		assertTrue(brussels.conflicts().isEmpty(), "while apart, neither office knows of a conflict");
		assertEquals("Buy A4 paper", ghent.todo(paper).title());
	}

	@Test
	void theConflictShowsTheSameOnEveryReplicaOnceTheyHaveSynced ( ) {
		network.healAll();
		network.synchronize();

		TodoConflict expected = new TodoConflict(paper, List.of(
				new ConflictingRename("Buy printer paper", brussels.replica(), 2),
				new ConflictingRename("Buy A4 paper", ghent.replica(), 1)));
		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertEquals(List.of(expected), replica.conflicts(), "on " + replica.replica());
			TodoView view = replica.todo(paper);
			assertTrue(view.inConflict());
			assertNull(view.title(), "no agreed title until someone settles it");
			assertEquals(Set.of("Buy printer paper", "Buy A4 paper"), view.concurrentTitles());
		}
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void nothingElseCanBeDoneWithATodoInConflict ( ) {
		network.healAll();
		network.synchronize();

		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertThrows(BusinessException.class, () -> replica.renameTodo(paper, "Buy paper, any kind"));
			assertThrows(BusinessException.class, () -> replica.markTodoOngoing(paper));
			assertThrows(BusinessException.class, () -> replica.dropTodo(paper));
		}
		assertEquals(3, brussels.operations().size(), "the refused actions decided nothing, so nothing was replicated");
	}

	@Test
	void aPersonSettlesItOnOneOfTheTitlesAndTheSettlementReachesEveryone ( ) {
		network.healAll();
		network.synchronize();

		ghent.settleTitle(paper, "Buy printer paper");     // ghent accepts brussels' wording
		assertFalse(ghent.todo(paper).inConflict(), "settled locally at once");
		assertEquals(1, antwerp.conflicts().size(), "antwerp has not heard yet");

		network.synchronize();

		for ( TodoListServer replica : List.of(brussels, ghent, antwerp) ) {
			assertTrue(replica.conflicts().isEmpty());
			assertEquals("Buy printer paper", replica.todo(paper).title());
		}
		antwerp.markTodoOngoing(paper);                     // and the to-do can be worked on again
		network.synchronize();
		assertEquals(TodoStatus.ONGOING, brussels.todo(paper).status());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aSettlementMayBeANewTitleThatDoesJusticeToBoth ( ) {
		network.healAll();
		network.synchronize();

		antwerp.settleTitle(paper, "Buy A4 printer paper");
		network.synchronize();

		assertEquals("Buy A4 printer paper", brussels.todo(paper).title());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void onlyATodoInConflictIsSettled ( ) {
		network.healAll();
		network.synchronize();
		TodoId keys = ghent.addTodo("Copy keys");

		assertThrows(BusinessException.class, () -> ghent.settleTitle(keys, "Copy the keys"), "nothing to settle: that is a rename");
		assertThrows(BusinessException.class, () -> ghent.settleTitle(paper, "Copy keys"), "another to-do has that title");
	}

	@Test
	void statusChangesMadeBeforeTheConflictWasKnownStillCount ( ) {
		ghent.markTodoOngoing(paper);           // ghent works on it, unaware brussels renamed it too
		network.healAll();
		network.synchronize();

		TodoView view = brussels.todo(paper);
		assertTrue(view.inConflict());
		assertEquals(TodoStatus.ONGOING, view.status(), "the conflict is about the title only");
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void twoOfficesSettlingTheSameWayWhileApartEndIt ( ) {
		network.healAll();
		network.synchronize();
		network.isolate(antwerp);

		ghent.settleTitle(paper, "Buy A4 paper");
		antwerp.settleTitle(paper, "Buy A4 paper");
		network.healAll();
		network.synchronize();

		assertTrue(brussels.conflicts().isEmpty(), "two concurrent settlements agreeing are no conflict");
		assertEquals("Buy A4 paper", brussels.todo(paper).title());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void twoOfficesSettlingDifferentlyWhileApartConflictAgain ( ) {
		network.healAll();
		network.synchronize();
		network.isolate(antwerp);

		ghent.settleTitle(paper, "Buy A4 paper");
		antwerp.settleTitle(paper, "Buy printer paper");
		network.healAll();
		network.synchronize();

		TodoConflict again = brussels.conflicts().get(0);
		assertEquals(List.of(antwerp.replica(), ghent.replica()), again.renames().stream().map(ConflictingRename::decidedBy).toList(),
				"the conflict is now between the two settlements");

		brussels.settleTitle(paper, "Buy A4 printer paper");
		network.synchronize();
		assertTrue(antwerp.conflicts().isEmpty());
		assertEquals("Buy A4 printer paper", ghent.todo(paper).title());
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aConflictOnlyHoldsBackTheOfficesThatKnowOfIt ( ) {
		network.healAll();
		network.isolate(antwerp);
		network.synchronize();
		assertEquals(1, ghent.conflicts().size());

		antwerp.markTodoOngoing(paper);         // antwerp has not heard of either rename, and goes ahead
		network.healAll();
		network.synchronize();

		assertEquals(TodoStatus.ONGOING, brussels.todo(paper).status());
		assertEquals(1, brussels.conflicts().size(), "the title still waits to be settled");
		assertConverged(brussels, ghent, antwerp);
	}

	@Test
	void aDropMadeWithoutKnowingOfTheConflictLeavesTheTitleToSettle ( ) {
		network.healAll();
		network.isolate(antwerp);
		network.synchronize();

		antwerp.dropTodo(paper);
		network.healAll();
		network.synchronize();

		assertEquals(TodoStatus.DROPPED, brussels.todo(paper).status());
		assertEquals(1, brussels.conflicts().size(), "dropped, but its title still in conflict");
		brussels.settleTitle(paper, "Buy paper");
		network.synchronize();
		assertTrue(ghent.conflicts().isEmpty());
		assertConverged(brussels, ghent, antwerp);
	}

}
