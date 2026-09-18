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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Objects;

/**
 * What the harness caught while running the thing under test, or the absence of it, plus the
 * assertions the published test bases make about it. One copy, used by {@code CommandTest},
 * {@code AggregateTest} and {@code TranslatorTest} alike, so the three cannot drift.
 * <p>
 * Two rules the assertions here keep, both of them things a test library gets wrong quietly:
 * <ul>
 * <li><b>Nothing is written to the console.</b> Everything a reader needs — the full cause chain,
 * the message that was actually compared — goes into the assertion message, and the throwable is
 * attached to the failure so a runner shows its stack trace under the assertion rather than
 * somewhere above it in the build log.</li>
 * <li><b>A null message is compared, never dereferenced.</b> A root cause with no message (a bare
 * {@link NullPointerException}, say) used to make the comparison itself throw, so the harness
 * reported an NPE of its own where an assertion failure was due.</li>
 * </ul>
 *
 * <h2>Which throwable an assertion is about</h2>
 * The <em>type</em> assertions judge the throwable that came out of the execution, exactly as a
 * {@code catch} block in application code would see it. That is deliberate rather than searching
 * the cause chain: a business rule belongs in the command, where its {@code BusinessException}
 * propagates unwrapped, and one judged inside a decision model arrives wrapped by the projector and
 * is reported by the kernel as a failure rather than as a rejection (see WHERE-VALIDATIONS-GO.md).
 * An assertion that accepted a wrapped {@code BusinessException} would bless exactly the shape the
 * framework treats as a bug. The failure message renders the whole chain, so a wrapped one is
 * obvious at a glance and can be asserted on by naming the wrapper.
 * <p>
 * The message-only assertion judges the <em>root cause</em>'s message, which is what it has always
 * done and what makes it useful across the wrapping the framework does on some paths.
 */
final class CaughtError {

	private final Throwable thrown;

	private CaughtError ( Throwable thrown ) {
		this.thrown = thrown;
	}

	/** The error a harness caught, or {@code null} for an execution that completed. */
	static CaughtError of ( Throwable thrown ) {
		return new CaughtError(thrown);
	}

	boolean isPresent ( ) {
		return thrown != null;
	}

	Throwable thrown ( ) {
		return thrown;
	}

	/**
	 * Asserts nothing was thrown. {@code what} names what was expected instead, so the failure reads
	 * as a sentence: {@code assertNone("expected 2 events")}.
	 */
	void assertNone ( String what ) {
		if ( thrown != null ) {
			fail("%s, but the execution failed with %s".formatted(what, chain()), thrown);
		}
	}

	/** Asserts something was thrown and its root cause carries {@code expectedMessage}. */
	void assertMessage ( String expectedMessage ) {
		if ( thrown == null ) {
			fail("expected a failure whose root cause says %s, but the execution completed"
					.formatted(quoted(expectedMessage)));
		}
		String actualMessage = rootCause(thrown).getMessage();
		if ( !Objects.equals(actualMessage, expectedMessage) ) {
			fail("root cause message was %s, expected %s — thrown: %s"
					.formatted(quoted(actualMessage), quoted(expectedMessage), chain()), thrown);
		}
	}

	/** Asserts the throwable that came out of the execution is of {@code expectedType}. */
	void assertType ( Class<? extends Throwable> expectedType ) {
		if ( expectedType == null ) {
			fail("no expected exception type given");
		}
		if ( thrown == null ) {
			fail("expected a %s, but the execution completed".formatted(expectedType.getName()));
		}
		if ( !expectedType.isInstance(thrown) ) {
			fail("expected a %s, but the execution threw %s".formatted(expectedType.getName(), chain()), thrown);
		}
	}

	/**
	 * Asserts the throwable that came out of the execution is of {@code expectedType} <em>and</em>
	 * carries {@code expectedMessage}. The message is that of the matched throwable, not of the root
	 * cause: the assertion is about one exception, so both halves are about the same one.
	 */
	void assertTypeAndMessage ( Class<? extends Throwable> expectedType, String expectedMessage ) {
		assertType(expectedType);
		String actualMessage = thrown.getMessage();
		if ( !Objects.equals(actualMessage, expectedMessage) ) {
			fail("%s message was %s, expected %s — thrown: %s".formatted(expectedType.getSimpleName(),
					quoted(actualMessage), quoted(expectedMessage), chain()), thrown);
		}
	}

	/** The whole cause chain on one line, e.g. {@code ProjectorException: run failed <- BusinessException: no funds}. */
	String chain ( ) {
		if ( thrown == null ) {
			return "nothing";
		}
		StringBuilder chain = new StringBuilder();
		for ( Throwable t = thrown; t != null; t = t.getCause() ) {
			if ( !chain.isEmpty() ) {
				chain.append(" <- ");
			}
			chain.append(t.getClass().getName()).append(": ").append(t.getMessage());
			if ( t.getCause() == t ) {
				break;
			}
		}
		return chain.toString();
	}

	private static Throwable rootCause ( Throwable t ) {
		Throwable root = t;
		while ( root.getCause() != null && root.getCause() != root ) {
			root = root.getCause();
		}
		return root;
	}

	private static String quoted ( String value ) {
		return value == null ? "null" : "'" + value + "'";
	}

}
