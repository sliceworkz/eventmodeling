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
package org.sliceworkz.eventmodeling.module.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * The default pacing of {@link Automation}: an idle automation waits its {@link Automation#idlePollInterval()},
 * a failing one backs off from {@link Automation#DEFAULT_POLL_INTERVAL} whatever its idle interval is.
 */
public class AutomationPacingTest {

	private static Automation<String,Object,Object> automation ( Duration idle ) {
		return new Automation<>() {
			@Override
			public TodoListReadModel<Object,String> getTodoList ( ) {
				throw new UnsupportedOperationException("not read by the pacing");
			}

			@Override
			public Optional<EventReference> handle ( String todoItem, AutomationContext<Object,Object> context ) {
				return Optional.empty();
			}

			@Override
			public Duration idlePollInterval ( ) {
				return idle != null ? idle : Automation.super.idlePollInterval();
			}
		};
	}

	@Test
	void anIdleAutomationWaitsThePollIntervalByDefault ( ) {
		assertEquals(Automation.DEFAULT_POLL_INTERVAL, automation(null).delayBeforeNextBatch(0, null));
	}

	@Test
	void anIdleAutomationWaitsTheIdleIntervalItChose ( ) {
		assertEquals(Duration.ofHours(1), automation(Duration.ofHours(1)).delayBeforeNextBatch(0, null));
	}

	@Test
	void aFailingAutomationBacksOffFromThePollIntervalWhateverItsIdleInterval ( ) {
		Automation<String,Object,Object> hourly = automation(Duration.ofHours(1));
		RuntimeException down = new RuntimeException("down");
		assertEquals(Duration.ofSeconds(10), hourly.delayBeforeNextBatch(1, down));
		assertEquals(Duration.ofSeconds(20), hourly.delayBeforeNextBatch(2, down));
		assertEquals(Automation.DEFAULT_MAX_BACKOFF, hourly.delayBeforeNextBatch(10, down));
	}
}
