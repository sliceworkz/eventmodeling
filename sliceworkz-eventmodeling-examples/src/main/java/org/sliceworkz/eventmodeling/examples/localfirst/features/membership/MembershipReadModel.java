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
package org.sliceworkz.eventmodeling.examples.localfirst.features.membership;

import java.util.List;
import java.util.Map;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.Member;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/** The offices this replica has heard of, where each stands, and which retired ones are not pruned yet. */
public class MembershipReadModel implements ReadModel<TodoEvent> {

	private final TodoListFold fold = new TodoListFold();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		fold.apply(event.data());
	}

	public List<Member> members ( ) {
		return fold.members();
	}

	public Map<ReplicaId, Long> prunable ( ) {
		return fold.prunable();
	}

}
