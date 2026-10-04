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
package org.sliceworkz.eventmodeling.mock.policy;

import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Whenever a {@link FirstDomainEvent} happens, record its value as a second one — except for a value
 * starting with {@code ignore}, which this policy lets pass.
 */
public class SecondFromFirstPolicy implements Policy<MockDomainEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forTypes(FirstDomainEvent.class);
	}

	@Override
	public Optional<Command<MockDomainEvent>> react ( Event<MockDomainEvent> event ) {
		if ( event.data() instanceof FirstDomainEvent first && !first.value().startsWith("ignore") ) {
			return Optional.of(new RecordSecondCommand(first.value()));
		}
		return Optional.empty();
	}

}
