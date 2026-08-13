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
package org.sliceworkz.eventmodeling.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class RetryPolicyTest {

	@Test
	void maxAttemptsCountsTheFirstAttemptSoZeroIsRejected ( ) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(0));
		assertTrue(failure.getMessage().contains("counts the first attempt"), failure.getMessage());
	}

	@Test
	void aNegativeMaxAttemptsIsRejected ( ) {
		assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(-1));
	}

	@Test
	void oneAttemptIsLegalAndMeansNoRetry ( ) {
		assertEquals(1, RetryPolicy.of(1).maxAttempts());
	}

	@Test
	void theDefaultIsThreeAttempts ( ) {
		assertEquals(3, RetryPolicy.DEFAULT.maxAttempts());
	}

}
