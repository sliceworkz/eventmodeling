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
package org.sliceworkz.eventmodeling.examples.localfirst.features.replicationlog;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.VectorClock;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * The operations this replica holds, in the order it stored them, and the vector clock they add up to.
 *
 * <p>This is what anti-entropy reads: a peer says what it has seen with its clock, and gets every operation
 * not covered by it. A replica only stores an operation once it holds everything the operation was decided
 * on, so its own order is causally consistent and a peer can take the answer in front to back.
 */
public class ReplicationLogReadModel implements ReadModel<TodoEvent> {

	private final List<TodoEvent> operations = new ArrayList<>();
	private VectorClock versionVector = VectorClock.empty();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		operations.add(event.data());
		versionVector = versionVector.mergedWith(event.data().stamp().clock());
	}

	public VectorClock versionVector ( ) {
		return versionVector;
	}

	/** The operations a replica that has seen {@code peer} does not hold yet. */
	public List<TodoEvent> missingAt ( VectorClock peer ) {
		return operations.stream().filter(op -> !op.stamp().seenBy(peer)).toList();
	}

	public List<TodoEvent> operations ( ) {
		return List.copyOf(operations);
	}

}
