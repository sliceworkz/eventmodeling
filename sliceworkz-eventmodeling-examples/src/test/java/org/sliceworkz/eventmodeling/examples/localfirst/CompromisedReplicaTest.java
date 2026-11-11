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

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.ReplicaDistrusted;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.Verdict;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.PendingReview;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;

/**
 * The brussels office is taken over at some point. The security office declares it distrusted from that
 * point on: its later operations are set aside everywhere, honest work that still holds without them
 * stands, and honest work that no longer does waits for a reviewer, blocking its to-do.
 */
class CompromisedReplicaTest extends ReplicaTestSupport {

	private final TodoListServer brussels = replica("brussels");
	private final TodoListServer ghent = replica("ghent");
	private final TodoListServer antwerp = replica("antwerp");
	private final TodoListServer security = replica("security");
	private final Network network = Network.peerToPeer(brussels, ghent, antwerp, security);

	private final List<TodoListServer> honest = List.of(ghent, antwerp, security);

	@Test
	void whatTheTakenOverOfficeDidIsSetAsideEverywhere ( ) {
		TodoId coffee = brussels.addTodo("Order coffee");     // still honest
		TodoId paper = ghent.addTodo("Buy paper");
		network.synchronize();

		long takenOverAfter = operationsOf(brussels);
		brussels.renameTodo(coffee, "Order poison");
		brussels.dropTodo(paper);
		brussels.addTodo("Wire money");
		network.synchronize();
		assertEquals("Order poison", ghent.todo(coffee).title(), "until the declaration, it is just a replica");

		security.distrustReplica(brussels.replica(), takenOverAfter, "office taken over");
		network.synchronize();

		for ( TodoListServer replica : List.of(brussels, ghent, antwerp, security) ) {
			assertEquals("Order coffee", replica.todo(coffee).title(), "on " + replica.replica());
			assertEquals(TodoStatus.PLANNED, replica.todo(paper).status());
			assertEquals(2, replica.todos().size(), "the attacker's to-do is gone");
			assertEquals(3, replica.setAside().size(), "and kept as forensic record");
		}
		assertEquals(List.of(new TodoListFold.Distrust(brussels.replica(), takenOverAfter, "office taken over")), ghent.distrusted());
		assertEquals(6, ghent.operations().size(), "nothing was deleted from any store: two adds, three compromised operations, the declaration");
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void honestWorkThatStillHoldsWithoutTheAttackerStandsUnreviewed ( ) {
		TodoId coffee = ghent.addTodo("Order coffee");
		network.synchronize();
		brussels.renameTodo(coffee, "Order poison");          // after the takeover
		network.synchronize();
		ghent.markTodoOngoing(coffee);                        // made having seen the rename, but not depending on it
		network.synchronize();

		security.distrustReplica(brussels.replica(), 0, "office taken over");
		network.synchronize();

		for ( TodoListServer replica : honest ) {
			assertTrue(replica.pendingReviews().isEmpty(), "starting it was allowed without the rename too");
			assertEquals(TodoStatus.ONGOING, replica.todo(coffee).status());
			assertEquals("Order coffee", replica.todo(coffee).title());
			assertFalse(replica.todo(coffee).underReview());
		}
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void honestWorkThatNoLongerHoldsWaitsForAReviewerAndBlocksTheTodo ( ) {
		TodoId coffee = dependentDoneOnCompromisedOngoing();

		for ( TodoListServer replica : honest ) {
			assertEquals(List.of(new PendingReview(coffee, "op/ghent/2", ghent.replica(), "marked done")), replica.pendingReviews(),
					"done needs ongoing, and only the attacker had started it");
			assertTrue(replica.todo(coffee).underReview());
			assertEquals(TodoStatus.DONE, replica.todo(coffee).status(), "shown as it stands, but marked");
			assertThrows(BusinessException.class, () -> replica.markTodoPlanned(coffee));
			assertThrows(BusinessException.class, () -> replica.renameTodo(coffee, "Order tea"));
		}
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aRevertedChangeIsSetAsideToo ( ) {
		TodoId coffee = dependentDoneOnCompromisedOngoing();

		antwerp.reviewChange(coffee, "op/ghent/2", Verdict.REVERTED);
		network.synchronize();

		for ( TodoListServer replica : honest ) {
			assertTrue(replica.pendingReviews().isEmpty());
			assertEquals(TodoStatus.PLANNED, replica.todo(coffee).status(), "as if neither the attacker nor what followed from it happened");
			assertFalse(replica.todo(coffee).underReview());
			assertTrue(replica.setAside().stream().anyMatch(a -> a.operationId().equals("op/ghent/2") && a.why().equals("reverted after review")));
		}
		ghent.markTodoOngoing(coffee);                        // and work goes on
		network.synchronize();
		assertEquals(TodoStatus.ONGOING, antwerp.todo(coffee).status());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aConfirmedChangeStands ( ) {
		TodoId coffee = dependentDoneOnCompromisedOngoing();

		antwerp.reviewChange(coffee, "op/ghent/2", Verdict.CONFIRMED);
		network.synchronize();

		assertTrue(ghent.pendingReviews().isEmpty());
		assertEquals(TodoStatus.DONE, ghent.todo(coffee).status(), "the work really was done");
		assertFalse(ghent.todo(coffee).underReview());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void twoReviewersDisagreeingWhileApartRevert ( ) {
		TodoId coffee = dependentDoneOnCompromisedOngoing();
		network.isolate(antwerp);

		ghent.reviewChange(coffee, "op/ghent/2", Verdict.CONFIRMED);
		antwerp.reviewChange(coffee, "op/ghent/2", Verdict.REVERTED);
		network.healAll();
		network.synchronize();

		assertEquals(TodoStatus.PLANNED, security.todo(coffee).status(), "the cautious verdict wins");
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void whatWasBuiltOnASuspectChangeIsJudgedAgainWhenThatChangeIsReverted ( ) {
		TodoId coffee = ghent.addTodo("Order coffee");
		network.synchronize();
		brussels.markTodoOngoing(coffee);                     // compromised
		network.synchronize();
		ghent.markTodoDone(coffee);                           // on the compromised start
		network.synchronize();
		antwerp.markTodoPlanned(coffee);                      // reopens what ghent finished
		network.synchronize();
		security.distrustReplica(brussels.replica(), 0, "office taken over");
		network.synchronize();

		assertEquals(List.of("op/ghent/2"), pendingIds(security), "reopening a done to-do still holds while ghent's done stands");

		ghent.reviewChange(coffee, "op/ghent/2", Verdict.REVERTED);
		network.synchronize();

		assertEquals(List.of("op/antwerp/1"), pendingIds(security), "without ghent's done there was nothing to reopen");
		assertTrue(security.todo(coffee).underReview());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void aTitleSettledOnTheAttackersRenameIsReviewed ( ) {
		TodoId paper = ghent.addTodo("Buy paper");
		network.synchronize();
		network.isolate(brussels);
		brussels.renameTodo(paper, "Buy bleach");            // compromised
		ghent.renameTodo(paper, "Buy A4 paper");
		network.healAll();
		network.synchronize();
		antwerp.settleTitle(paper, "Buy bleach");            // the reviewer of the conflict took the attacker's side
		network.synchronize();

		security.distrustReplica(brussels.replica(), 0, "office taken over");
		network.synchronize();

		assertEquals(List.of("op/antwerp/1"), pendingIds(ghent), "without the attacker there was no conflict to settle");
		assertEquals("Buy bleach", ghent.todo(paper).title());

		ghent.reviewChange(paper, "op/antwerp/1", Verdict.REVERTED);
		network.synchronize();
		assertEquals("Buy A4 paper", antwerp.todo(paper).title());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void honestWorkOnATodoOnlyTheAttackerCreatedIsSetAside ( ) {
		TodoId wire = brussels.addTodo("Wire money");        // compromised from the start
		network.synchronize();
		ghent.markTodoOngoing(wire);
		network.synchronize();

		security.distrustReplica(brussels.replica(), 0, "office taken over");
		network.synchronize();

		assertTrue(antwerp.todos().isEmpty());
		assertEquals("about a to-do only distrusted operations created",
				antwerp.setAside().stream().filter(a -> a.decidedBy().equals(ghent.replica())).findFirst().orElseThrow().why());
		assertConverged(brussels, ghent, antwerp, security);
	}

	@Test
	void onlyTheSecurityOfficeDistrustsAndACutOnlyMovesEarlier ( ) {
		assertThrows(BusinessException.class, () -> ghent.distrustReplica(brussels.replica(), 0, "I do not like them"));
		assertThrows(BusinessException.class, () -> security.distrustReplica(security.replica(), 0, "oops"));

		security.distrustReplica(brussels.replica(), 5, "office taken over");
		assertThrows(BusinessException.class, () -> security.distrustReplica(brussels.replica(), 7, "later"));
		security.distrustReplica(brussels.replica(), 3, "it started earlier");
		assertEquals(3, security.distrusted().get(0).trustedUpTo());
	}

	@Test
	void aDeclarationFromAnyoneButTheSecurityOfficeCountsForNothing ( ) {
		TodoId coffee = ghent.addTodo("Order coffee");
		network.synchronize();
		// the attacker at brussels tries to turn the mechanism against ghent
		var forged = new ReplicaDistrusted(ghent.replica(), 0, "they are the attackers",
				OperationStamp.next(brussels.replica(), brussels.versionVector()));
		antwerp.receive(List.of(forged));

		assertTrue(antwerp.distrusted().isEmpty());
		assertEquals("Order coffee", antwerp.todo(coffee).title());
	}

	/** Ghent finishes a to-do the attacker had started, after which the takeover is declared. */
	private TodoId dependentDoneOnCompromisedOngoing ( ) {
		TodoId coffee = ghent.addTodo("Order coffee");       // op/ghent/1
		network.synchronize();
		brussels.markTodoOngoing(coffee);                     // compromised
		network.synchronize();
		ghent.markTodoDone(coffee);                           // op/ghent/2, allowed only because the attacker started it
		network.synchronize();
		security.distrustReplica(brussels.replica(), 0, "office taken over");
		network.synchronize();
		return coffee;
	}

	private static long operationsOf ( TodoListServer replica ) {
		return replica.versionVector().counterOf(replica.replica());
	}

	private static List<String> pendingIds ( TodoListServer replica ) {
		return replica.pendingReviews().stream().map(PendingReview::operationId).toList();
	}

}
