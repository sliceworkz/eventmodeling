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
package org.sliceworkz.eventmodeling.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.commands.BusinessException;

/**
 * Which exceptions a monitored port counts as business answers, and which of its methods it summarizes.
 */
class PortMonitoringTest {

	static class Declined extends RuntimeException { private static final long serialVersionUID = 1L; }
	static class HardDeclined extends Declined { private static final long serialVersionUID = 1L; }
	static class Unreachable extends PortUnavailableException {
		private static final long serialVersionUID = 1L;
		Unreachable ( ) { super("down"); }
	}

	@Test
	void perCallIsTheDefaultShape ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall();
		assertEquals(PortMonitoring.Mode.PER_CALL, monitoring.mode());
		assertNull(monitoring.interval());
		assertTrue(monitoring.summarizedMethods().isEmpty());
		assertFalse(monitoring.isSummarized("anything"));
		assertEquals("per call", monitoring.toString());
	}

	@Test
	void aBusinessExceptionIsABusinessAnswerByDefault ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall();
		assertTrue(monitoring.isBusinessException(new BusinessException("no")));
		assertTrue(monitoring.isBusinessException(new BusinessException("no") { private static final long serialVersionUID = 1L; }),
				"a subtype too");
	}

	@Test
	void anythingElseIsAFailureByDefault ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall();
		assertFalse(monitoring.isBusinessException(new IllegalStateException()));
		assertFalse(monitoring.isBusinessException(new Declined()));
		assertFalse(monitoring.isBusinessException(new IOException()));
		assertFalse(monitoring.isBusinessException(new UncheckedIOException(new IOException())));
		assertFalse(monitoring.isBusinessException(new StackOverflowError()));
		assertFalse(monitoring.isBusinessException(null));
	}

	@Test
	void aDeclaredTypeAndItsSubtypesAreBusinessAnswers ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall().businessExceptions(Declined.class);
		assertTrue(monitoring.isBusinessException(new Declined()));
		assertTrue(monitoring.isBusinessException(new HardDeclined()));
		assertTrue(monitoring.isBusinessException(new BusinessException("still")), "declaring adds to the default");
		assertFalse(monitoring.isBusinessException(new IllegalStateException()));
		assertEquals(List.of(Declined.class), monitoring.businessExceptionTypes());
	}

	@Test
	void declarationsAccumulateWithoutDuplicates ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall()
				.businessExceptions(Declined.class)
				.businessExceptions(Declined.class, IOException.class);
		assertEquals(List.of(Declined.class, IOException.class), monitoring.businessExceptionTypes());
	}

	@Test
	void aPortUnavailableExceptionIsNeverABusinessAnswer ( ) {
		assertFalse(PortMonitoring.perCall().isBusinessException(new Unreachable()));
		assertFalse(PortMonitoring.perCall().isBusinessException(new PortUnavailableException("down")));
	}

	@Test
	void declaringAPortUnavailableTypeOrASupertypeOfItIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().businessExceptions(PortUnavailableException.class));
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().businessExceptions(Unreachable.class));
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().businessExceptions(RuntimeException.class),
				"a supertype would sweep the outages up with the answers");
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().businessExceptions(Exception.class));
	}

	@Test
	void anEmptyOrNullDeclarationIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().businessExceptions());
		assertThrows(NullPointerException.class, () -> PortMonitoring.perCall().businessExceptions(Declined.class, null));
	}

	@Test
	void aSummarizedPortSummarizesEveryMethod ( ) {
		PortMonitoring monitoring = PortMonitoring.summarized(Duration.ofMinutes(1));
		assertEquals(PortMonitoring.Mode.SUMMARIZED, monitoring.mode());
		assertEquals(Duration.ofMinutes(1), monitoring.interval());
		assertTrue(monitoring.isSummarized("anything"));
		assertEquals("summarized every PT1M", monitoring.toString());
	}

	@Test
	void summarizingNamesTheMethodsOfAPerCallPort ( ) {
		PortMonitoring monitoring = PortMonitoring.perCall().summarizing(Duration.ofSeconds(30), "isMember", " lookup ");
		assertEquals(PortMonitoring.Mode.PER_CALL, monitoring.mode());
		assertEquals(Set.of("isMember", "lookup"), monitoring.summarizedMethods());
		assertTrue(monitoring.isSummarized("isMember"));
		assertTrue(monitoring.isSummarized("lookup"), "the name is stripped");
		assertFalse(monitoring.isSummarized("addMember"));
	}

	@Test
	void anIntervalBelowOneSecondIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.summarized(Duration.ofMillis(999)));
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.summarized(null));
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().summarizing(Duration.ZERO, "x"));
	}

	@Test
	void summarizingWithoutMethodsOrOnASummarizedPortIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().summarizing(Duration.ofMinutes(1)));
		assertThrows(IllegalArgumentException.class, () -> PortMonitoring.perCall().summarizing(Duration.ofMinutes(1), " "));
		assertThrows(IllegalStateException.class, () -> PortMonitoring.summarized(Duration.ofMinutes(1)).summarizing(Duration.ofMinutes(1), "x"));
	}

	@Test
	void aMonitoringIsImmutable ( ) {
		PortMonitoring base = PortMonitoring.perCall();
		base.businessExceptions(Declined.class);
		base.summarizing(Duration.ofMinutes(1), "x");
		assertTrue(base.businessExceptionTypes().isEmpty());
		assertTrue(base.summarizedMethods().isEmpty());
	}

}
