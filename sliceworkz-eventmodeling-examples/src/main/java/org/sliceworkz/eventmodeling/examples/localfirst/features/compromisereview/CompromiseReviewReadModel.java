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
package org.sliceworkz.eventmodeling.examples.localfirst.features.compromisereview;

import java.util.List;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.Distrust;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.PendingReview;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListFold.SetAside;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * What the security office and the reviewers look at: the replicas distrusted and from where, the
 * operations set aside (the compromised ones, kept as forensic record, and the reverted ones), and the
 * honest changes made on compromised input that wait for a reviewer.
 */
public class CompromiseReviewReadModel implements ReadModel<TodoEvent> {

	private final TodoListFold fold = new TodoListFold();

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(TodoEvent.class);
	}

	@Override
	public void when ( Event<TodoEvent> event ) {
		fold.apply(event.data());
	}

	public List<Distrust> distrusted ( ) {
		return fold.distrusted();
	}

	public List<SetAside> setAside ( ) {
		return fold.setAside();
	}

	public List<PendingReview> pendingReviews ( ) {
		return fold.pendingReviews();
	}

}
