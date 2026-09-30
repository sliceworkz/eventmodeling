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
package org.sliceworkz.eventmodeling.mock.publishing;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * One item's values, in order: a live read model the size of a decision model, the kind a publisher
 * reads as of an event.
 */
public class ItemReadModel implements ReadModel<MockDomainEvent> {

	public static final String ITEM = "item";

	private final String itemId;
	private final List<String> values = new ArrayList<>();

	public ItemReadModel ( String itemId ) {
		this.itemId = itemId;
	}

	public static Tags tags ( String itemId ) {
		return Tags.of(ITEM, itemId);
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), tags(itemId));
	}

	@Override
	public void when ( Event<MockDomainEvent> event ) {
		switch ( event.data() ) {
			case FirstDomainEvent e -> values.add(e.value());
			case SecondDomainEvent e -> values.add(e.value());
			default -> { }
		}
	}

	public String state ( ) {
		return String.join(",", values);
	}

}
