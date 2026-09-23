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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A command that never selects its decision models has decided on nothing.
 * <p>
 * The {@code CommandResult} events are raised on is handed out by {@code decisionModels(...)} and
 * {@code noDecisionModels()} alone, so a command that called neither raised nothing: its execution
 * succeeds, appends nothing, and behaves exactly as if it had called {@code noDecisionModels()}.
 * <p>
 * Plain {@code @Test}: this is framework behaviour, not storage behaviour.
 */
public class CommandWithoutDecisionModelsTest extends AbstractMockDomainTest {

	@Test
	void aCommandThatNeverSelectsItsDecisionModelsDecidesOnNothing ( ) {
		Mock domain = domain();

		Optional<EventReference> reference = domain.execute(new SilentCommand());

		assertTrue(reference.isEmpty(), "nothing was raised, so nothing was appended");
		assertTrue(eventStore().getRawEventStream(EventStreamId.anyContext())
				.query(EventQuery.matchAll()).isEmpty(), "the store stays empty");
	}

	@Test
	void theSameHoldsForACommandWithAResult ( ) {
		Mock domain = domain();

		CommandExecutionResult<String> result = domain.execute(new SilentCommandWithResult());

		assertEquals("answered, but nothing was decided on", result.response());
		assertTrue(result.eventReference().isEmpty());
	}

	@Test
	void aCommandThatDecidesOnNothingSaysSoAndIsFine ( ) {
		Mock domain = domain();

		domain.execute(new DecidesOnNothingCommand());
	}

	private Mock domain ( ) {
		return buildBoundedContext(
				BoundedContext.newBuilder(Mock.class)
					.name("UnitTestBoundedContext")
					.eventStorage(eventStorage())
					.instance(InstanceFactory.determine("unittests")));
	}

	static class SilentCommand implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			// selects no decision models: nothing to decide on, nothing to raise
		}
	}

	static class SilentCommandWithResult implements CommandWithResult<MockDomainEvent,String> {
		@Override
		public String execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			return "answered, but nothing was decided on";
		}
	}

	static class DecidesOnNothingCommand implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.noDecisionModels();
		}
	}

}
