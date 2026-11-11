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

import java.util.TreeSet;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.Verdict;

/**
 * Three offices, each with its own to-do list server, working through a network outage.
 *
 * <pre>
 * mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.localfirst.LocalFirstTodoExample"
 * </pre>
 */
public class LocalFirstTodoExample {

	public static void main ( String[] args ) {
		try ( TodoListServer brussels = TodoListServer.named("brussels");
			  TodoListServer ghent = TodoListServer.named("ghent");
			  TodoListServer antwerp = TodoListServer.named("antwerp");
			  TodoListServer security = TodoListServer.named(TodoListDomain.SECURITY_OFFICE.value()) ) {

			Network network = Network.peerToPeer(brussels, ghent, antwerp, security);

			TodoId coffee = brussels.addTodo("Order coffee");
			TodoId paper = ghent.addTodo("Buy paper");
			brussels.markTodoOngoing(coffee);
			network.synchronize();
			print("1. everyone in sync", brussels, ghent, antwerp);

			network.isolate(antwerp);
			brussels.markTodoDone(coffee);
			antwerp.markTodoPlanned(coffee);              // antwerp does not know it is done, and has a reason to replan
			brussels.renameTodo(paper, "Buy printer paper");
			antwerp.renameTodo(paper, "Buy A4 paper");    // the same to-do, renamed differently
			antwerp.addTodo("Copy keys");
			network.synchronize();
			print("2. antwerp cut off, everyone keeps working", brussels, ghent, antwerp);

			network.rejoin(antwerp);
			network.synchronize();
			print("3. connection back: the status settled itself (earliest state wins), the title is a conflict", brussels, ghent, antwerp);
			brussels.conflicts().forEach(c -> System.out.println("  conflicting renames: " + c.renames()));
			try {
				ghent.markTodoOngoing(paper);
			} catch ( BusinessException refused ) {
				System.out.println("  ghent tries to start it: " + refused.getMessage());
			}

			ghent.settleTitle(paper, "Buy A4 printer paper");  // a person settles it, here on a new title
			ghent.markTodoOngoing(paper);
			network.synchronize();
			print("4. ghent settled the title and started the to-do; everyone has it", brussels, ghent, antwerp);

			long takenOverAfter = brussels.versionVector().counterOf(brussels.replica());
			TodoId keys = antwerp.todos().stream().filter(t -> "Copy keys".equals(t.title())).findFirst().orElseThrow().todoId();
			brussels.renameTodo(keys, "Copy the master keys");  // brussels has been taken over
			brussels.markTodoOngoing(coffee);
			network.synchronize();
			ghent.markTodoDone(coffee);                           // honest, but possible only because brussels started it
			network.synchronize();
			security.distrustReplica(brussels.replica(), takenOverAfter, "office taken over");
			network.synchronize();
			print("5. brussels was taken over; the security office distrusts it from operation " + (takenOverAfter + 1) + " on", brussels, ghent, antwerp);
			ghent.setAside().forEach(a -> System.out.println("  set aside: " + a.decidedBy() + " " + a.change() + " (" + a.why() + ")"));
			ghent.pendingReviews().forEach(p -> System.out.println("  awaiting review: " + p.decidedBy() + " " + p.change() + " (" + p.operationId() + ")"));

			antwerp.reviewChange(coffee, ghent.pendingReviews().get(0).operationId(), Verdict.REVERTED);
			network.synchronize();
			print("6. antwerp reverted ghent's change; nothing of the takeover is left", brussels, ghent, antwerp);

			try ( TodoListServer leuven = TodoListServer.joining("leuven", ghent) ) {
				network.join(leuven, brussels, ghent, antwerp, security);
				leuven.markTodoOngoing(coffee);
				antwerp.retire();
				network.synchronize();
				ghent.pruneRetiredOffices();
				network.synchronize();
				ghent.markTodoDone(coffee);
				network.synchronize();
				print("7. leuven joined through ghent, antwerp retired and was pruned from new stamps", brussels, ghent, leuven);
				ghent.members().forEach(m -> System.out.println("  office " + m.replica() + ": " + m.status()
						+ (m.sponsoredBy() == null ? "" : ", sponsored by " + m.sponsoredBy())));
				System.out.println("  ghent's latest stamp: " + ghent.operations().getLast().stamp());
			}
		}
	}

	private static void print ( String title, TodoListServer... replicas ) {
		System.out.println();
		System.out.println(title);
		for ( TodoListServer replica : replicas ) {
			System.out.println("  " + replica);
			replica.state().forEach(t -> System.out.println("     "
					+ (t.inConflict() ? "TITLE IN CONFLICT " + new TreeSet<>(t.concurrentTitles()) : t.title())
					+ ": " + t.status()
					+ (t.concurrentStatuses().size() > 1 ? "   (concurrent statuses: " + new TreeSet<>(t.concurrentStatuses()) + ")" : "")
					+ (t.underReview() ? "   UNDER REVIEW" : "")));
		}
	}

}
