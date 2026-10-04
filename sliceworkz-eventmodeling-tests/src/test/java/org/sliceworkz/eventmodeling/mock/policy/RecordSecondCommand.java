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

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Raises a {@link SecondDomainEvent} for a value, tagged {@code item:<value>}; rejects a value starting with
 * {@code reject} — the command a {@link SecondFromFirstPolicy} issues.
 */
public record RecordSecondCommand ( String value ) implements Command<MockDomainEvent> {

	@Override
	public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
		var result = context.noDecisionModels();
		BusinessException.when(value.startsWith("reject"), "rejected " + value);
		result.raiseEvent(new SecondDomainEvent(value), Tags.of("item", value));
	}

}
