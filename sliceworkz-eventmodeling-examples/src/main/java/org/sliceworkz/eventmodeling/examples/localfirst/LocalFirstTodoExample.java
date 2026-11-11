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

import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;

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
			  TodoListServer antwerp = TodoListServer.named("antwerp") ) {

			Network network = Network.peerToPeer(brussels, ghent, antwerp);

			TodoId coffee = brussels.addTodo("Order coffee");
			TodoId paper = ghent.addTodo("Buy paper");
			brussels.markTodoOngoing(coffee);
			network.synchronize();
			print("1. everyone in sync", brussels, ghent, antwerp);

			network.isolate(antwerp);
			brussels.markTodoDone(coffee);
			antwerp.markTodoPlanned(coffee);   // antwerp does not know it is done
			antwerp.dropTodo(paper);
			TodoId keys = antwerp.addTodo("Copy keys");
			ghent.markTodoOngoing(paper);
			network.synchronize();
			print("2. antwerp cut off, everyone keeps working", brussels, ghent, antwerp);

			network.rejoin(antwerp);
			network.synchronize();
			print("3. connection back: one state everywhere, one conflict to resolve", brussels, ghent, antwerp);
			brussels.conflicts().forEach(c -> System.out.println("  conflict on '" + c.title() + "': " + c.changes()));

			ghent.markTodoOngoing(keys);
			ghent.resolveConflict(coffee, TodoStatus.PLANNED);   // a person decides which side stands
			network.synchronize();
			print("4. ghent resolved the conflict; the resolution reached everyone", brussels, ghent, antwerp);
		}
	}

	private static void print ( String title, TodoListServer... replicas ) {
		System.out.println();
		System.out.println(title);
		for ( TodoListServer replica : replicas ) {
			System.out.println("  " + replica);
			replica.state().forEach(t -> System.out.println("     " + t.title() + ": "
					+ (t.inConflict() ? "IN CONFLICT " + t.concurrentStatuses()
							: t.status() + (t.concurrentStatuses().size() > 1 ? "   (concurrent: " + t.concurrentStatuses() + ")" : ""))));
		}
	}

}
