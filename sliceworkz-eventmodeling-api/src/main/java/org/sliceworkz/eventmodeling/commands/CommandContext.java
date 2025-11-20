/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.commands;

import java.util.Optional;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;

public interface CommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	<T> T read ( Class<? extends ReadModel<? extends CONSUMED_EVENT_TYPE>> readModelClass, Object... constructorParams);
	
	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> noDecisionModels ( );
	
	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> decisionModels ( @SuppressWarnings("unchecked") DecisionModel<CONSUMED_EVENT_TYPE>... decisionModels );
	
	Optional<EventReference> getEventReference ( EventId eventId );

}
