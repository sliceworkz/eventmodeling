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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;

/**
 * A command that never selects its decision models is told so.
 * <p>
 * Choosing what a command decides on — {@code decisionModels(...)}, or {@code noDecisionModels()} when
 * it decides on nothing — is what produces the {@code CommandResult} its events are raised on, so a
 * command that does neither has no consistency boundary to append under and nothing to append. The
 * failure has to name the command at fault and the line missing from it.
 * <p>
 * Plain {@code @Test}: nothing here reaches storage.
 */
public class CommandWithoutDecisionModelsTest extends AbstractMockDomainTest {

	@Test
	void aCommandThatNeverSelectsItsDecisionModelsIsNamedInTheFailure ( ) {
		Mock domain = domain();

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> domain.execute(new ForgetfulCommand()));

		assertTrue(thrown.getMessage().contains("Forgetful"), "should name the command: " + thrown.getMessage());
		assertTrue(thrown.getMessage().contains("noDecisionModels"), "should name the way out: " + thrown.getMessage());
	}

	@Test
	void theSameHoldsForACommandWithAResult ( ) {
		Mock domain = domain();

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> domain.execute(new ForgetfulCommandWithResult()));

		// the suffix is only stripped when it is a suffix, so this one keeps its whole simple name
		assertTrue(thrown.getMessage().contains("ForgetfulCommandWithResult"), "should name the command: " + thrown.getMessage());
	}

	/** Deciding on nothing is legitimate — it just has to be said. */
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

	static class ForgetfulCommand implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			// forgets to select any decision models
		}
	}

	static class ForgetfulCommandWithResult implements CommandWithResult<MockDomainEvent,String> {
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
