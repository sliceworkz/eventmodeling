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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * A caught-up processor parks for its poll interval, unless its projector holds back a move of the
 * bookmark's read position that falls due sooner: parked for the whole interval with no append to wake
 * it, the read position would trail the stream that long.
 */
public class ProjectorProcessorParkingTimeTest {

	@Test
	void nothingHeldBackParksForThePollInterval ( ) {
		assertEquals(10_000, ProjectorProcessor.parkingTime(Optional.empty()));
	}

	@Test
	void aMoveDueSoonerParksUntilItIsDue ( ) {
		assertEquals(1_501, ProjectorProcessor.parkingTime(Optional.of(Duration.ofMillis(1_500))));
	}

	@Test
	void aMoveDueNowParksBriefly ( ) {
		assertEquals(1, ProjectorProcessor.parkingTime(Optional.of(Duration.ZERO)));
	}

	@Test
	void aMoveDueLaterThanThePollIntervalParksForThePollInterval ( ) {
		assertEquals(10_000, ProjectorProcessor.parkingTime(Optional.of(Duration.ofSeconds(30))));
	}

}
