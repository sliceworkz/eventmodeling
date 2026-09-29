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
package org.sliceworkz.eventmodeling.mock.ports;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * A live read model that looks every event up through the gateway while it is projected — so those calls
 * are attributed to the read model, also when it is read from inside a command.
 */
public class GatewayLookupReadModel implements ReadModel<MockDomainEvent> {

	private final GatewayPort gateway;
	private final List<String> answers = new ArrayList<>();

	public GatewayLookupReadModel ( GatewayPort gateway ) {
		this.gateway = gateway;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
	}

	@Override
	public void when ( Event<MockDomainEvent> event ) {
		if ( event.data() instanceof FirstDomainEvent first ) {
			answers.add(gateway.answer(first.value()));
		}
	}

	public List<String> answers ( ) {
		return answers;
	}

}
