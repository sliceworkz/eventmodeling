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
package org.sliceworkz.eventmodeling.examples.localfirst.features.marktodoongoing;

import java.util.Set;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedOngoing;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/** Starts a planned to-do. Decided on this replica's view only; the operation it raises is replicated as it is. */
public record MarkTodoOngoingCommand ( ReplicaId replica, TodoId todoId ) implements Command<TodoEvent> {

	private static final Set<TodoStatus> ALLOWED_FROM = Set.of(TodoStatus.PLANNED);

	@Override
	public void execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		var todo = state.todo(todoId);
		BusinessException.when(todo.isEmpty(), "To-do " + todoId.value() + " does not exist");
		TodoStatus current = todo.get().resolvedStatus();
		BusinessException.when(!ALLOWED_FROM.contains(current), "To-do '" + todo.get().title() + "' is " + current);

		OperationStamp stamp = state.nextStamp(replica);
		result.raiseEvent(new TodoMarkedOngoing(todoId, stamp),
				TodoListDomain.tagsOf(todoId, replica), stamp.operationId());
	}

}
