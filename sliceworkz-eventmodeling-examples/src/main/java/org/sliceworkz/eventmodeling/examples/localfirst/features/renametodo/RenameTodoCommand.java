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
package org.sliceworkz.eventmodeling.examples.localfirst.features.renametodo;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoRenamed;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/**
 * Renames a to-do on one replica. Two offices renaming the same to-do to different titles while apart is a
 * conflict no rule can settle: which wording is right is for a person to say ({@code SettleTodoTitle}).
 */
public record RenameTodoCommand ( ReplicaId replica, TodoId todoId, String title ) implements Command<TodoEvent> {

	@Override
	public void execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		BusinessException.when(title == null || title.isBlank(), "A to-do needs a title");
		var todo = state.todo(todoId);
		BusinessException.when(todo.isEmpty(), "To-do " + todoId.value() + " does not exist");
		BusinessException.when(todo.get().underReview(), "To-do '" + todo.get().displayTitle() + "' has changes made on compromised input awaiting review");
		BusinessException.when(todo.get().inConflict(), "To-do '" + todo.get().displayTitle() + "' was renamed concurrently; settle its title first");
		BusinessException.when(todo.get().resolvedStatus() == TodoStatus.DROPPED, "To-do '" + todo.get().displayTitle() + "' is DROPPED");
		BusinessException.when(title.strip().equals(todo.get().agreedTitle()), "To-do is already called '" + title.strip() + "'");
		BusinessException.when(state.titleTakenByAnother(title.strip(), todoId), "There already is a to-do '" + title.strip() + "'");

		OperationStamp stamp = state.nextStamp(replica);
		result.raiseEvent(new TodoRenamed(todoId, title.strip(), stamp),
				TodoListDomain.tagsOf(todoId, replica), stamp.operationId());
	}

}
