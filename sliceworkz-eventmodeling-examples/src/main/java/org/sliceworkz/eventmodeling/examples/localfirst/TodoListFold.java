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

import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoAdded;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoDropped;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedDone;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedOngoing;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedPlanned;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoRenamed;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoTitleSettled;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.MultiValueRegister;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.VectorClock;

/**
 * The one fold of the to-do list, used by the decision model a command decides on and by the read models a
 * user reads, so they cannot disagree on what the operations mean.
 *
 * <p>A to-do is two registers, each written by operations: its title and its status. They differ in what
 * concurrent writes mean. Concurrent status changes resolve by a business rule, automatically
 * ({@link TodoStatus#CONCURRENT_PRIORITY}). Concurrent renames to different titles have no rule that could
 * pick the right one: they are a <b>conflict</b>, which a person settles, and until then nothing else may be
 * done with the to-do. The replica's vector clock is the merge of every operation's clock it holds.
 */
public final class TodoListFold {

	/** One to-do as the fold holds it. */
	public record Todo ( TodoId todoId, MultiValueRegister<String> title, MultiValueRegister<TodoStatus> status ) {

		/** Whether concurrent renames are waiting for someone to settle the title. */
		public boolean inConflict ( ) {
			return title.values().size() > 1;
		}

		/** The title every replica agrees on; {@code null} while renames are in conflict. */
		public String agreedTitle ( ) {
			return inConflict() ? null : title.values().iterator().next();
		}

		/** The title to show and sort by: the agreed one, or the first candidate while in conflict. */
		public String displayTitle ( ) {
			return title.values().stream().sorted().findFirst().orElseThrow();
		}

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
		switch ( event ) {
			case TodoAdded e -> todos.putIfAbsent(e.todoId(), new Todo(e.todoId(),
					MultiValueRegister.<String>empty().write(e.stamp(), e.title()),
					MultiValueRegister.<TodoStatus>empty().write(e.stamp(), TodoStatus.PLANNED)));
			case TodoRenamed e -> writeTitle(e, e.title());
			case TodoTitleSettled e -> writeTitle(e, e.title());
			case TodoMarkedPlanned e -> writeStatus(e, TodoStatus.PLANNED);
			case TodoMarkedOngoing e -> writeStatus(e, TodoStatus.ONGOING);
			case TodoMarkedDone e -> writeStatus(e, TodoStatus.DONE);
			case TodoDropped e -> writeStatus(e, TodoStatus.DROPPED);
		}
	}

	private void writeTitle ( TodoEvent event, String title ) {
		todos.computeIfPresent(event.todoId(), (id, t) -> new Todo(id, t.title().write(event.stamp(), title), t.status()));
	}

	private void writeStatus ( TodoEvent event, TodoStatus status ) {
		todos.computeIfPresent(event.todoId(), (id, t) -> new Todo(id, t.title(), t.status().write(event.stamp(), status)));
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
				.sorted(Comparator.comparing(Todo::displayTitle).thenComparing(t -> t.todoId().value()))
				.toList();
	}

}
