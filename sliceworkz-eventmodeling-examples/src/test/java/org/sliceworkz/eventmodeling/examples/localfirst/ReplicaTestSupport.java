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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;

/** Starts replicas, closes them after each test, and checks they converged. */
abstract class ReplicaTestSupport {

	private final List<TodoListServer> started = new ArrayList<>();

	protected TodoListServer replica ( String name ) {
		TodoListServer server = TodoListServer.named(name);
		started.add(server);
		return server;
	}

	@AfterEach
	void closeReplicas ( ) {
		started.forEach(TodoListServer::close);
	}

	/**
	 * Strong eventual consistency: replicas holding the same operations are in the same state, whatever
	 * order and path the operations took to get there.
	 */
	protected static void assertConverged ( TodoListServer... replicas ) {
		TodoListServer first = replicas[0];
		for ( TodoListServer other : replicas ) {
			assertEquals(operationIds(first), operationIds(other), "the operations held by " + first.replica() + " and " + other.replica());
			assertEquals(first.versionVector(), other.versionVector(), "the version vectors of " + first.replica() + " and " + other.replica());
			assertEquals(first.state(), other.state(), "the to-do lists of " + first.replica() + " and " + other.replica());
			assertEquals(first.conflicts(), other.conflicts(), "the conflicts known to " + first.replica() + " and " + other.replica());
			assertEquals(0, other.waitingOperations(), "operations still waiting on " + other.replica());
		}
	}

	protected static Set<String> operationIds ( TodoListServer replica ) {
		return replica.operations().stream().map(op -> op.stamp().operationId()).collect(Collectors.toSet());
	}

}
