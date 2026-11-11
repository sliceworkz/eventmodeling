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
package org.sliceworkz.eventmodeling.examples.localfirst.features.addtodo;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoAdded;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/**
 * Adds a to-do on one replica. The id is minted here, so adds on different replicas never collide.
 *
 * <p>The unique-title rule is checked against what <i>this</i> replica knows. Two replicas adding the same
 * title while apart both succeed, and after they sync the list holds the title twice: an invariant over
 * several operations cannot be guaranteed without coordination, which is the price of working local first.
 */
public record AddTodoCommand ( ReplicaId replica, String title ) implements CommandWithResult<TodoEvent, TodoId> {

	@Override
	public TodoId execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		BusinessException.when(title == null || title.isBlank(), "A to-do needs a title");
		BusinessException.when(state.activeTitle(title.strip()), "There already is a to-do '" + title.strip() + "'");

		TodoId todoId = TodoListDomain.TODO.newId();
		OperationStamp stamp = state.nextStamp(replica);
		result.raiseEvent(new TodoAdded(todoId, title.strip(), stamp),
				TodoListDomain.tagsOf(todoId, replica), stamp.operationId());
		return todoId;
	}

}
