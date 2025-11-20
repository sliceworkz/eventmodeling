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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import java.util.List;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventstore.events.Tags;

public class MockCommand implements Command<MockDomainEvent> {

	private List<MockDomainEvent> events; 
	
	public MockCommand ( List<MockDomainEvent> events ) {
		this.events = events;
	}
	
	@Override
	public CommandResult<MockDomainEvent,MockDomainEvent> execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
		var result = context.noDecisionModels();
		
		for ( var event: events ) {
			result.raiseEvent(event, Tags.none());
		}
		
		return result;
	}

}
