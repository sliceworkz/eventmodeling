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
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.Member;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.MemberStatus;

/**
 * Offices joining, retiring, and being removed, while every one keeps working on its own. Correctness
 * needs no membership at all; membership is what lets the clocks shrink, and what says an office is done.
 */
class MembershipTest extends ReplicaTestSupport {

	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final TodoListServer security = replica("security");
	private final Network network = Network.peerToPeer(brussels, ghent, antwerp, security);

	@Test
	void aNewOfficeCatchesUpFromItsSponsorAndJoinsWithoutAnyoneAgreeing ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		ghent.addTodo("Buy paper");
		network.synchronize();
		network.isolate(brussels);                           // nobody needs to be reachable for a newcomer

		TodoListServer leuven = joining("leuven", ghent);
		assertEquals(ghent.state(), leuven.state(), "caught up from its sponsor before doing anything");
		network.join(leuven, brussels, ghent, antwerp, security);

		leuven.markTodoOngoing(coffee);
		network.synchronize();
		assertEquals(TodoStatus.ONGOING, antwerp.todo(coffee).status());

		network.healAll();
		network.synchronize();
		assertEquals(TodoStatus.ONGOING, brussels.todo(coffee).status());
		assertTrue(brussels.members().contains(new Member(leuven.replica(), MemberStatus.ACTIVE, ghent.replica(), null)));
		assertConverged(brussels, ghent, antwerp, security, leuven);
	}

	@Test
	void aRetiredOfficeMakesNoMoreChanges ( ) {
		TodoId coffee = antwerp.addTodo("Order coffee");
		antwerp.retire();
		network.synchronize();

		assertThrows(BusinessException.class, () -> antwerp.addTodo("Buy paper"));
		assertThrows(BusinessException.class, () -> antwerp.markTodoOngoing(coffee));
		assertThrows(BusinessException.class, antwerp::retire);
		assertEquals(new Member(antwerp.replica(), MemberStatus.RETIRED, null, 2L), memberOn(ghent, antwerp));
		assertEquals(TodoStatus.PLANNED, ghent.todo(coffee).status(), "what it did stands");
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aRetiredOfficeIsPrunedFromNewStampsAndLaterChangesStillReadAsLater ( ) {
		TodoId coffee = antwerpRenamesCoffeeAndRetires();

		assertEquals(1, brussels.pruneRetiredOffices(), "knowing of the retirement is enough");
		assertEquals(0, brussels.pruneRetiredOffices(), "and once is enough");
		assertEquals(0, antwerp.pruneRetiredOffices(), "a retired office prunes nothing");
		network.synchronize();
		assertEquals(MemberStatus.PRUNED, memberOn(security, antwerp).status());

		ghent.renameTodo(coffee, "Order the best coffee");
		assertFalse(lastStampOf(ghent).clock().counters().containsKey("antwerp"), "new stamps leave the retired office out");
		network.synchronize();

		for ( TodoListServer office : List.of(brussels, ghent, antwerp, security) ) {
			assertEquals("Order the best coffee", office.todo(coffee).title(),
					"the rename is read as after antwerp's, not as concurrent with it, although its stamp has no entry for antwerp");
			assertFalse(office.todo(coffee).inConflict());
		}
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aNewcomerAfterPruningReadsTheOldStampsRight ( ) {
		TodoId coffee = antwerpRenamesCoffeeAndRetires();
		brussels.pruneRetiredOffices();
		network.synchronize();
		ghent.renameTodo(coffee, "Order the best coffee");
		network.synchronize();

		TodoListServer leuven = joining("leuven", brussels);
		network.join(leuven, brussels, ghent, antwerp, security);

		assertEquals("Order the best coffee", leuven.todo(coffee).title());
		assertFalse(lastStampOf(leuven).clock().counters().containsKey("antwerp"));
		network.synchronize();
		assertConverged(brussels, ghent, antwerp, security, leuven);
	}

	@Test
	void aPruningReachesAnOfficeOnlyAfterTheRetiredOfficesOperations ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();
		antwerp.renameTodo(coffee, "Order good coffee");
		antwerp.retire();
		network.sync(antwerp, brussels);                    // only brussels hears of it
		brussels.pruneRetiredOffices();
		brussels.markTodoOngoing(coffee);                   // stamped without antwerp's entry

		var brusselsLatest = ghent.receive(List.of(brussels.operations().getLast()));
		assertEquals(0, brusselsLatest, "it waits: what it relies on has not reached ghent");
		ghent.receive(brussels.operationsMissingAt(ghent.versionVector()));

		assertEquals(0, ghent.waitingOperations());
		assertEquals("Order good coffee", ghent.todo(coffee).title());
		assertEquals(TodoStatus.ONGOING, ghent.todo(coffee).status());
		network.synchronize();
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aLostOfficeIsRemovedAndWhatItDidAfterwardsIsIgnored ( ) {
		TodoId coffee = antwerp.addTodo("Order coffee");
		network.synchronize();
		long lastHeardFrom = antwerp.versionVector().counterOf(antwerp.replica());

		network.isolate(antwerp);                            // the office burns down, with work unsent
		antwerp.renameTodo(coffee, "Order cheap coffee");
		antwerp.addTodo("Buy a fire extinguisher");
		security.removeOffice(antwerp.replica(), lastHeardFrom, "office closed");
		network.synchronize();

		network.healAll();                                   // its disk turns up after all
		network.synchronize();

		assertEquals("Order coffee", ghent.todo(coffee).title());
		assertEquals(1, ghent.todos().size());
		assertEquals(MemberStatus.REMOVED, memberOn(ghent, antwerp).status());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void anOfficeThatWasAwayALongTimeMergesWhenItComesBack ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();
		network.isolate(antwerp);
		for ( int i = 0; i < 10; i++ ) {
			ghent.addTodo("Task " + i);
			antwerp.addTodo("Away task " + i);
			network.synchronize();
		}
		antwerp.markTodoOngoing(coffee);

		network.rejoin(antwerp);
		network.synchronize();

		assertEquals(21, brussels.todos().size());
		assertEquals(TodoStatus.ONGOING, brussels.todo(coffee).status());
		assertEquals(MemberStatus.ACTIVE, memberOn(brussels, antwerp).status(), "away is not gone: nothing ended it");
		assertConverged(brussels, ghent, antwerp, security);
	}

	private TodoId antwerpRenamesCoffeeAndRetires ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");
		network.synchronize();
		antwerp.renameTodo(coffee, "Order good coffee");
		antwerp.retire();
		network.synchronize();
		return coffee;
	}

	private static Member memberOn ( TodoListServer office, TodoListServer member ) {
		return office.members().stream().filter(m -> m.replica().equals(member.replica())).findFirst().orElseThrow();
	}

	private static OperationStamp lastStampOf ( TodoListServer office ) {
		return office.operations().stream()
				.filter(op -> op.stamp().origin().equals(office.replica()))
				.reduce((a, b) -> b).orElseThrow().stamp();
	}

}
