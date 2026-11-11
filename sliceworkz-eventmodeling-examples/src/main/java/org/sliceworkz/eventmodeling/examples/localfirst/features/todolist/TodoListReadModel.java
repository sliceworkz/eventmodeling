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
package org.sliceworkz.eventmodeling.examples.localfirst.features.todolist;

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * The to-do list as the user of one replica sees it. Live: projected per read from the replica's own
 * store, so what a user just did shows at once, whatever the other replicas are up to.
 */
public class TodoListReadModel implements ReadModel<TodoEvent> {

	/**
	 * One to-do.
	 *
	 * <p>{@code concurrentStatuses} holds more than one status when replicas changed it without knowing
	 * about each other; {@code status} is the one the business rule picked, so there always is one.
	 *
	 * <p>{@code concurrentTitles} holds more than one title when replicas renamed it differently without
	 * knowing about each other. That is a conflict: {@code inConflict} is set and {@code title} is
	 * {@code null}, as there is no agreed title until a person settles it.
	 */
	public record TodoView ( TodoId todoId, String title, Set<String> concurrentTitles, TodoStatus status, Set<TodoStatus> concurrentStatuses, boolean inConflict ) { }

	private final TodoListFold fold = new TodoListFold();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		fold.apply(event.data());
	}

	/** The to-dos not dropped. */
	public List<TodoView> todos ( ) {
		return everything().stream().filter(t -> t.status() != TodoStatus.DROPPED).toList();
	}

	/** Every to-do, the dropped ones included: what two replicas compare to tell they converged. */
	public List<TodoView> everything ( ) {
		return fold.todos().stream()
				.map(t -> new TodoView(t.todoId(), t.agreedTitle(), t.title().values(), t.resolvedStatus(), t.status().values(), t.inConflict()))
				.toList();
	}

}
