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
package org.sliceworkz.eventmodeling.examples.localfirst.features.resolvetodoconflict;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoConflictResolved;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/**
 * A person decides which of the conflicting changes stands. Only one of the statuses that were in conflict
 * can be chosen: the resolution picks a side, it is no new change in disguise.
 *
 * <p>Like every command, it is decided on this replica's knowledge alone. Two offices resolving the same
 * conflict while apart is possible: choosing the same status settles it, choosing differently is a new
 * conflict between the two resolutions, to be resolved again.
 */
public record ResolveTodoConflictCommand ( ReplicaId replica, TodoId todoId, TodoStatus chosen ) implements Command<TodoEvent> {

	@Override
	public void execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		var todo = state.todo(todoId);
		BusinessException.when(todo.isEmpty(), "To-do " + todoId.value() + " does not exist");
		BusinessException.when(!todo.get().inConflict(), "To-do '" + todo.get().title() + "' has no conflict to resolve");
		BusinessException.when(!todo.get().status().values().contains(chosen),
				"To-do '" + todo.get().title() + "' can only be resolved to one of " + todo.get().status().values());

		OperationStamp stamp = state.nextStamp(replica);
		result.raiseEvent(new TodoConflictResolved(todoId, chosen, stamp),
				TodoListDomain.tagsOf(todoId, replica), stamp.operationId());
	}

}
