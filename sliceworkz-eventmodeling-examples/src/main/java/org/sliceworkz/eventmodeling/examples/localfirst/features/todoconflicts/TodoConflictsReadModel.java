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
package org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts;

import java.util.List;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * The to-dos waiting for a person to settle their title, as far as this replica knows: for each, the
 * renames that were made without knowledge of each other, and which office made them.
 *
 * <p>A conflict shows here on every replica that holds both sides; an office that has not synced yet
 * does not know of it, and is not held back by it either.
 */
public class TodoConflictsReadModel implements ReadModel<TodoEvent> {

	/** One side of a conflict: the title an office chose, and its operation on that office. */
	public record ConflictingRename ( String title, ReplicaId decidedBy, long operation ) { }

	public record TodoConflict ( TodoId todoId, List<ConflictingRename> renames ) { }

	private final TodoListFold fold = new TodoListFold();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		fold.apply(event.data());
	}

	public List<TodoConflict> conflicts ( ) {
		return fold.todos().stream()
				.filter(TodoListFold.Todo::inConflict)
				.map(t -> new TodoConflict(t.todoId(), t.title().writes().stream()
						.map(w -> new ConflictingRename(w.value(), w.stamp().origin(), w.stamp().counter()))
						.toList()))
				.toList();
	}

}
