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
package org.sliceworkz.eventmodeling.module.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * A summary window is aligned to the wall clock, so windows of several instances cover the same span.
 */
class PortSummaryWindowTest {

	@Test
	void aWindowStartsOnAMultipleOfItsInterval ( ) {
		Duration minute = Duration.ofMinutes(1);
		assertEquals(Instant.parse("2026-09-29T10:15:00Z"), PortReporter.windowStart(Instant.parse("2026-09-29T10:15:00Z"), minute));
		assertEquals(Instant.parse("2026-09-29T10:15:00Z"), PortReporter.windowStart(Instant.parse("2026-09-29T10:15:59.999Z"), minute));
		assertEquals(Instant.parse("2026-09-29T10:16:00Z"), PortReporter.windowStart(Instant.parse("2026-09-29T10:16:00.001Z"), minute));
	}

	@Test
	void twoInstancesCountingAtDifferentMomentsShareTheWindow ( ) {
		Duration fiveMinutes = Duration.ofMinutes(5);
		assertEquals(
				PortReporter.windowStart(Instant.parse("2026-09-29T10:10:01Z"), fiveMinutes),
				PortReporter.windowStart(Instant.parse("2026-09-29T10:14:58Z"), fiveMinutes));
	}

}
