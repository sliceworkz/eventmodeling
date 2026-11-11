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
package org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate;

import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.Todo;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * What a command of this replica decides on: the to-dos as this replica knows them, and its vector clock,
 * from which the stamp of the operation it raises is made.
 *
 * <p>It reads every operation, because the stamp depends on all of them. So the DCB boundary of every
 * command is this replica's whole stream: two commands racing on one replica conflict, and the retry
 * re-decides and takes the next sequence number. Between replicas there is no boundary at all; that is
 * what the conflict resolution of the CRDT is for.
 */
public class ReplicaStateDecisionModel implements DecisionModel<TodoEvent> {

	private final TodoListFold fold = new TodoListFold();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		fold.apply(event.data());
	}

	public Optional<Todo> todo ( TodoId todoId ) {
		return fold.todo(todoId);
	}

	/** Whether a to-do not dropped carries this title, as far as this replica knows. */
	public boolean activeTitle ( String title ) {
		return fold.todos().stream().anyMatch(t -> !t.dropped() && t.title().equalsIgnoreCase(title));
	}

	/** The stamp of the operation this replica raises next, based on everything it has seen. */
	public OperationStamp nextStamp ( ReplicaId replica ) {
		return OperationStamp.next(replica, fold.seen());
	}

}
