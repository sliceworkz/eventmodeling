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
package org.sliceworkz.eventmodeling.module.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ElapsedTest {

	@Test
	void measuresInMicrosecondsOnTheMonotonicClock ( ) throws InterruptedException {
		long started = Elapsed.start();
		Thread.sleep(5);
		long micros = Elapsed.microsSince(started);
		assertTrue(micros >= 5_000, "slept 5ms, measured " + micros + "µs");
		assertTrue(micros < 5_000_000, "slept 5ms, measured " + micros + "µs");
	}

	@Test
	void neverReportsANegativeDuration ( ) {
		assertEquals(0, Elapsed.microsSince(System.nanoTime() + 1_000_000_000L));
	}

	@Test
	void describesADurationInTheUnitThatKeepsItReadable ( ) {
		assertEquals("0 µs", Elapsed.describe(0));
		assertEquals("850 µs", Elapsed.describe(850));
		assertEquals("1.0 ms", Elapsed.describe(1_000));
		assertEquals("12.4 ms", Elapsed.describe(12_400));
		assertEquals("999.9 ms", Elapsed.describe(999_900));
		assertEquals("1.00 s", Elapsed.describe(999_999));
		assertEquals("3.21 s", Elapsed.describe(3_210_000));
	}

}
