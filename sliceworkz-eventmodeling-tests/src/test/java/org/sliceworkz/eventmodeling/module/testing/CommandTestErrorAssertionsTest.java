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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.CommandTest;

/**
 * What the published {@link CommandTest} can assert about a failure, and what it says when the
 * assertion does not hold.
 * <p>
 * The harness is a library like any other, so its own failure paths need pinning: every scenario here
 * asserts on the {@link AssertionFailedError} the harness raises rather than on a passing assertion,
 * which is the only way to catch a message that names the wrong string or a comparison that throws
 * before it can compare. Four things it could not do, and now can:
 * <ul>
 * <li><b>Assert a type.</b> {@code error(String)} compares message text only, so the one distinction
 * WHERE-VALIDATIONS-GO.md asks a command to keep — a {@code BusinessException} rejection against an
 * {@code IllegalStateException} bug — was inexpressible. {@link CommandTest.TestResult#businessError}
 * and the {@code error(Class, ...)} pair express it.</li>
 * <li><b>Compare a null message.</b> A root cause with no message made the comparison itself throw,
 * so the harness reported an NPE where an assertion failure was due.</li>
 * <li><b>Report the string it compared.</b> The failure message printed the thrown exception's
 * message while the comparison was against the root cause's, so a mismatch named a string that was
 * never compared.</li>
 * <li><b>Keep the console out of it.</b> {@code noEvents()} printed the produced events to
 * {@code System.out} and {@code noException()} called {@code printStackTrace()}, putting the evidence
 * for a failure somewhere other than in the failure.</li>
 * </ul>
 * Framework behaviour rather than storage behaviour, so a plain {@code @Test} against the in-memory
 * store — the harness does not read differently per backend.
 */
public class CommandTestErrorAssertionsTest extends CommandTest<MockDomainEvent,MockInboundEvent,MockOutboundEvent> {

	/** Raises nothing and throws whatever it was built with. */
	record ThrowingCommand ( RuntimeException failure ) implements Command<MockDomainEvent> {

		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.noDecisionModels();
			throw failure;
		}

	}

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	// ---------------------------------------------------------------- asserting a type

	@Test
	void aBusinessRejectionIsAssertableByTypeAndByReason ( ) {
		given()
			.when(new ThrowingCommand(new BusinessException("insufficient balance")))
			.then()
			.businessError("insufficient balance");

		given()
			.when(new ThrowingCommand(new BusinessException("insufficient balance")))
			.then()
			.businessError();
	}

	/**
	 * The distinction the type assertion exists for: the same rule thrown as an
	 * {@code IllegalStateException} is a bug by the framework's taxonomy, and the message-only
	 * assertion cannot tell the two apart while {@code businessError} can.
	 */
	@Test
	void aRuleThrownAsABugIsNotABusinessRejectionThoughItSaysTheSameThing ( ) {
		var result = given()
			.when(new ThrowingCommand(new IllegalStateException("insufficient balance")))
			.then();

		// what the harness could always say: the text matches, whatever threw it
		result.error("insufficient balance");

		// what it can say now
		AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> result.businessError("insufficient balance"));
		assertAll(
			() -> assertTrue(failure.getMessage().contains(BusinessException.class.getName()),
				"the failure should name the type that was expected, was: " + failure.getMessage()),
			() -> assertTrue(failure.getMessage().contains(IllegalStateException.class.getName()),
				"the failure should name the type that was thrown, was: " + failure.getMessage()));
	}

	@Test
	void assertingATypeAgainstACommandThatSucceededFailsRatherThanPassing ( ) {
		var result = given()
			.when(new MockCommand(List.of(new FirstDomainEvent("a"))))
			.then();

		assertThrows(AssertionFailedError.class, () -> result.businessError());
		assertThrows(AssertionFailedError.class, () -> result.error(BusinessException.class, "anything"));
	}

	/**
	 * A type assertion judges what came out of the execution, not what is buried in its cause chain —
	 * so a rule judged somewhere that wraps it is reported as the wrapper it is, with the chain in the
	 * message rather than quietly accepted as a rejection.
	 */
	@Test
	void aWrappedBusinessExceptionIsNotAcceptedAsABusinessRejectionAndTheChainIsReported ( ) {
		var result = given()
			.when(new ThrowingCommand(new IllegalStateException("projecting failed", new BusinessException("insufficient balance"))))
			.then();

		AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> result.businessError());
		assertTrue(failure.getMessage().contains("insufficient balance"),
			"the failure should render the whole cause chain, was: " + failure.getMessage());

		// the message-only assertion still reaches the root cause, as it always has
		result.error("insufficient balance");
		// and the wrapper is what the type assertion names
		result.error(IllegalStateException.class, "projecting failed");
	}

	// ---------------------------------------------------------------- the null message

	/**
	 * A root cause with no message used to make {@code rootCause(e).getMessage().equals(..)} throw, so
	 * a test expecting one message and getting a bare NPE failed with a second NPE naming nothing.
	 */
	@Test
	void aRootCauseWithoutAMessageIsComparedRatherThanDereferenced ( ) {
		var result = given()
			.when(new ThrowingCommand(new NullPointerException()))
			.then();

		AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> result.error("insufficient balance"));
		assertTrue(failure.getMessage().contains("insufficient balance"),
			"the failure should name the message that was expected, was: " + failure.getMessage());

		// and a genuinely absent message is assertable
		result.error(NullPointerException.class, null);
	}

	// ---------------------------------------------------------------- the reported string

	/**
	 * The failure message used to print the thrown exception's message while comparing the root
	 * cause's, so a mismatch reported a string the assertion had never looked at.
	 */
	@Test
	void theFailureReportsTheStringItActuallyCompared ( ) {
		var result = given()
			.when(new ThrowingCommand(new IllegalStateException("the wrapper says this", new BusinessException("the root cause says this"))))
			.then();

		AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> result.error("something else entirely"));
		assertAll(
			() -> assertTrue(failure.getMessage().contains("the root cause says this"),
				"the failure should report the root cause's message, which is what was compared, was: " + failure.getMessage()),
			() -> assertTrue(failure.getMessage().contains("something else entirely"),
				"the failure should report the expected message, was: " + failure.getMessage()));
	}

	// ---------------------------------------------------------------- nothing on the console

	@Test
	void aFailedAssertionWritesNothingToTheConsoleAndPutsTheEvidenceInTheFailure ( ) {
		var raised = given()
			.when(new MockCommand(List.of(new FirstDomainEvent("a"))))
			.then();
		var failed = given()
			.when(new ThrowingCommand(new BusinessException("no")))
			.then();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		AssertionFailedError unexpectedEvents;
		AssertionFailedError unexpectedFailure;
		try {
			System.setOut(new PrintStream(out));
			System.setErr(new PrintStream(err));
			unexpectedEvents = assertThrows(AssertionFailedError.class, () -> raised.noEvents());
			unexpectedFailure = assertThrows(AssertionFailedError.class, () -> failed.noEvents());
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}

		assertAll(
			() -> assertEquals("", out.toString(), "the harness must not print the events it produced"),
			() -> assertEquals("", err.toString(), "the harness must not print a stack trace"),
			() -> assertTrue(unexpectedEvents.getMessage().contains("FirstDomainEvent"),
				"the produced events belong in the failure, was: " + unexpectedEvents.getMessage()),
			() -> assertEquals(BusinessException.class, unexpectedFailure.getCause().getClass(),
				"the failure the harness did not expect is attached to the assertion, not printed"));
	}

}
