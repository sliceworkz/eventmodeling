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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoAdded;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.MultiValueRegister;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.VectorClock;

/**
 * The one fold of the to-do list, used by the decision model a command decides on and by the read model a
 * user reads, so the two cannot disagree on what the operations mean.
 *
 * <p>A to-do is its title (written once) and a status register; the replica's vector clock is the merge of
 * every operation's clock it holds.
 */
public final class TodoListFold {

	/** One to-do as the fold holds it. */
	public record Todo ( TodoId todoId, String title, MultiValueRegister<TodoStatus> status ) {

		public TodoStatus resolvedStatus ( ) {
			return status.resolved(TodoStatus.CONCURRENT_PRIORITY);
		}

		public boolean dropped ( ) {
			return resolvedStatus() == TodoStatus.DROPPED;
		}

	}

	private final Map<TodoId, Todo> todos = new LinkedHashMap<>();
	private VectorClock seen = VectorClock.empty();

	public void apply ( TodoEvent event ) {
		seen = seen.mergedWith(event.stamp().clock());
		if ( event instanceof TodoAdded added ) {
			todos.putIfAbsent(added.todoId(), new Todo(added.todoId(), added.title(), MultiValueRegister.empty()));
		}
		todos.computeIfPresent(event.todoId(), (id, todo) -> new Todo(id, todo.title(),
				todo.status().write(event.stamp(), TodoListDomain.statusWrittenBy(event))));
	}

	public VectorClock seen ( ) {
		return seen;
	}

	public Optional<Todo> todo ( TodoId todoId ) {
		return Optional.ofNullable(todos.get(todoId));
	}

	/** Every to-do, the dropped ones included, in an order every replica agrees on. */
	public List<Todo> todos ( ) {
		return todos.values().stream()
				.sorted(Comparator.comparing(Todo::title).thenComparing(t -> t.todoId().value()))
				.toList();
	}

	public Set<TodoStatus> concurrentStatuses ( TodoId todoId ) {
		return todo(todoId).map(t -> t.status().values()).orElse(Set.of());
	}

}
