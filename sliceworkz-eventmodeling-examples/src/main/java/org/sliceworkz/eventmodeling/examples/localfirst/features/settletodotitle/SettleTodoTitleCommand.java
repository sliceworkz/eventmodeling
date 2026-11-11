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
package org.sliceworkz.eventmodeling.examples.localfirst.features.settletodotitle;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoTitleSettled;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/**
 * A person settles a to-do renamed concurrently by several offices: one of the conflicting titles, or a
 * new one that does justice to both. Only a to-do in conflict can be settled; otherwise it is a rename.
 *
 * <p>Like every command, it is decided on this replica's knowledge alone. Two offices settling the same
 * conflict while apart is possible: settling on the same title ends it, on different titles is a new
 * conflict between the two settlements, to be settled again.
 */
public record SettleTodoTitleCommand ( ReplicaId replica, TodoId todoId, String title ) implements Command<TodoEvent> {

	@Override
	public void execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		BusinessException.when(title == null || title.isBlank(), "A to-do needs a title");
		var todo = state.todo(todoId);
		BusinessException.when(todo.isEmpty(), "To-do " + todoId.value() + " does not exist");
		BusinessException.when(!todo.get().inConflict(), "To-do '" + todo.get().displayTitle() + "' has no conflicting titles to settle");
		BusinessException.when(state.titleTakenByAnother(title.strip(), todoId), "There already is a to-do '" + title.strip() + "'");

		OperationStamp stamp = state.nextStamp(replica);
		result.raiseEvent(new TodoTitleSettled(todoId, title.strip(), stamp),
				TodoListDomain.tagsOf(todoId, replica), stamp.operationId());
	}

}
