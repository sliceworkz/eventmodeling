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

import java.util.Set;

import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Publishes an item's state on every {@link FirstDomainEvent} about it: as of that event, or — when built
 * with {@code latest} — as it is when the publication runs.
 */
public class ItemPublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {

	private final boolean latest;

	public ItemPublisher ( ) {
		this(false);
	}

	public ItemPublisher ( boolean latest ) {
		this.latest = latest;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forTypes(FirstDomainEvent.class);
	}

	@Override
	public Set<Class<? extends ReadModel<? extends MockDomainEvent>>> reads ( ) {
		return Set.of(ItemReadModel.class);
	}

	@Override
	public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) {
		String itemId = event.tags().tag(ItemReadModel.ITEM).orElseThrow().value();
		ItemReadModel item = latest
				? context.readLatest(ItemReadModel.class, itemId)
				: context.readAsOfEvent(ItemReadModel.class, itemId);
		context.publish(new SomeOutboundEvent(itemId + ":" + item.state()), ItemReadModel.tags(itemId));
	}

}
