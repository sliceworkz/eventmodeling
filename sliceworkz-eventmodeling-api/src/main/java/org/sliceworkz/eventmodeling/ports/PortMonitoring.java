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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.sliceworkz.eventmodeling.commands.BusinessException;

/**
 * How the calls made through a port are reported, set on the binding:
 * <pre>
 *   .adapter(gateway).monitored().forPort(PaymentGateway.class)
 *   .adapter(membership).monitored(PortMonitoring.summarized(Duration.ofMinutes(1))).forPort(ProjectMembership.class)
 *   .adapter(gateway)
 *       .monitored(PortMonitoring.perCall().businessExceptions(PaymentDeclinedException.class))
 *       .forPort(PaymentGateway.class)
 * </pre>
 * A monitored port is handed out as a proxy that times every call and sorts how it ended into one of three
 * outcomes, reported as a {@code BoundedContextEvent} of its own:
 * <ul>
 *   <li>{@code PortCalled} — the call returned;</li>
 *   <li>{@code PortCallRejected} — it threw a <em>business</em> exception: the port answered "no" (declined,
 *       not found, not allowed), which is an answer and not a problem. Carries the exception's type and
 *       message, and no stack trace;</li>
 *   <li>{@code PortCallFailed} — it threw anything else: something is wrong (unreachable, timed out, a bug in
 *       the adapter). Carries the whole {@code Failure}, stack trace included, like {@code CommandFailed}.</li>
 * </ul>
 *
 * <h2>Which exceptions are business exceptions</h2>
 * The rule a command's outcome follows: a {@link BusinessException} is a business exception, and anything
 * else is a failure. {@link #businessExceptions(Class...)} adds the exception types an adapter throws that
 * do not extend {@code BusinessException} — typically those of a third-party client. A
 * {@link PortUnavailableException} is never one: it says the port could not be reached, and declaring it a
 * business answer would stop an outage from counting as a failure, so it is refused.
 *
 * <h2>Per call, or summarized</h2>
 * {@link #perCall()} — the default — reports every call as it ends. A busy port (one called per event
 * of a projection, or on every request) would add as many appends to the monitoring store as it makes
 * calls, so {@link #summarized(Duration)} condenses its calls into one {@code PortCallsSummarized} per port,
 * method and caller per interval — counts per outcome, the time blocked, and a latency histogram over
 * {@link PortLatencyBuckets}. A failure is still reported on its own in summarized mode, the first of each
 * exception type per method per interval, since a failure is the one outcome somebody opens to read; the
 * rest are counted. {@link #summarizing(Duration, String...)} summarizes the named methods of an otherwise
 * per-call port — the one hot lookup on a port whose other methods are rare.
 * <p>
 * The {@code BoundedContextObserver} is told of every call in either mode: the mode only decides what
 * reaches the monitoring stream.
 * <p>
 * Only an interface can be monitored — the proxy is a {@code java.lang.reflect.Proxy} — and the method
 * names given to {@code summarizing} must be methods of the port; both are checked when the binding is made.
 */
public final class PortMonitoring {

	/** How a call's outcomes are reported. */
	public enum Mode {
		/** Every call is reported as it ends. */
		PER_CALL,
		/** Calls are counted and reported once per interval. */
		SUMMARIZED
	}

	private final Mode mode;
	private final Duration interval;
	private final Set<String> summarizedMethods;
	private final List<Class<? extends Throwable>> businessExceptions;

	private PortMonitoring ( Mode mode, Duration interval, Set<String> summarizedMethods, List<Class<? extends Throwable>> businessExceptions ) {
		this.mode = mode;
		this.interval = interval;
		this.summarizedMethods = Set.copyOf(summarizedMethods);
		this.businessExceptions = List.copyOf(businessExceptions);
	}

	/**
	 * Every call reported as it ends — the default.
	 *
	 * @return per-call monitoring
	 */
	public static PortMonitoring perCall ( ) {
		return new PortMonitoring(Mode.PER_CALL, null, Set.of(), List.of());
	}

	/**
	 * Every method of the port summarized per interval.
	 *
	 * @param interval how long a summary window lasts; windows are aligned to the wall clock, so windows of
	 *        several instances line up. At least one second
	 * @return summarized monitoring
	 */
	public static PortMonitoring summarized ( Duration interval ) {
		return new PortMonitoring(Mode.SUMMARIZED, validInterval(interval), Set.of(), List.of());
	}

	/**
	 * Summarizes the named methods of a per-call port, per interval, and keeps reporting the others per call.
	 *
	 * @param interval how long a summary window lasts, at least one second
	 * @param methods the method names to summarize; each must be a method of the port it is bound to
	 * @return a copy with those methods summarized
	 */
	public PortMonitoring summarizing ( Duration interval, String... methods ) {
		if ( mode == Mode.SUMMARIZED ) {
			throw new IllegalStateException("every method of a summarized port is summarized already");
		}
		if ( methods == null || methods.length == 0 ) {
			throw new IllegalArgumentException("name at least one method to summarize, or use PortMonitoring.summarized(interval) for all of them");
		}
		Set<String> names = new LinkedHashSet<>(summarizedMethods);
		for ( String method : methods ) {
			if ( method == null || method.isBlank() ) {
				throw new IllegalArgumentException("a method name to summarize cannot be blank");
			}
			names.add(method.strip());
		}
		return new PortMonitoring(mode, validInterval(interval), names, businessExceptions);
	}

	/**
	 * Adds exception types that are business answers of the port: a call throwing one of them (or a
	 * subtype) is reported as {@code PortCallRejected}, not as {@code PortCallFailed}.
	 * {@link BusinessException} is one already.
	 *
	 * @param types the exception types
	 * @return a copy with those types added
	 * @throws IllegalArgumentException for a {@link PortUnavailableException} type, which is always a failure
	 */
	@SafeVarargs
	public final PortMonitoring businessExceptions ( Class<? extends Throwable>... types ) {
		if ( types == null || types.length == 0 ) {
			throw new IllegalArgumentException("name at least one business exception type");
		}
		List<Class<? extends Throwable>> all = new java.util.ArrayList<>(businessExceptions);
		for ( Class<? extends Throwable> type : types ) {
			Objects.requireNonNull(type, "a business exception type cannot be null");
			if ( PortUnavailableException.class.isAssignableFrom(type) || type.isAssignableFrom(PortUnavailableException.class) ) {
				throw new IllegalArgumentException(("%s cannot be a business exception: a PortUnavailableException says the port could not be reached, "
						+ "and counting it as an answer would stop an outage from counting as a failure").formatted(type.getName()));
			}
			if ( !all.contains(type) ) {
				all.add(type);
			}
		}
		return new PortMonitoring(mode, interval, summarizedMethods, all);
	}

	/**
	 * @return how calls are reported, for a method not named in {@link #summarizedMethods()}
	 */
	public Mode mode ( ) {
		return mode;
	}

	/**
	 * @return the summary interval, or {@code null} for a per-call port that summarizes no method
	 */
	public Duration interval ( ) {
		return interval;
	}

	/**
	 * @return the methods of a per-call port that are summarized; empty for a summarized port, whose every
	 *         method is
	 */
	public Set<String> summarizedMethods ( ) {
		return summarizedMethods;
	}

	/**
	 * @return the exception types added as business answers, beyond {@link BusinessException}
	 */
	public List<Class<? extends Throwable>> businessExceptionTypes ( ) {
		return businessExceptions;
	}

	/**
	 * @param method a method name of the port
	 * @return whether calls of that method are summarized
	 */
	public boolean isSummarized ( String method ) {
		return mode == Mode.SUMMARIZED || summarizedMethods.contains(method);
	}

	/**
	 * Whether a throwable a call ended with is a business answer of the port, rather than a failure.
	 *
	 * @param thrown what the call threw
	 * @return {@code true} for a {@link BusinessException} or a declared business exception type, never for a
	 *         {@link PortUnavailableException}
	 */
	public boolean isBusinessException ( Throwable thrown ) {
		if ( thrown == null || thrown instanceof PortUnavailableException ) {
			return false;
		}
		if ( thrown instanceof BusinessException ) {
			return true;
		}
		for ( Class<? extends Throwable> type : businessExceptions ) {
			if ( type.isInstance(thrown) ) {
				return true;
			}
		}
		return false;
	}

	private static Duration validInterval ( Duration interval ) {
		if ( interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0 ) {
			throw new IllegalArgumentException("a summary interval must be at least one second, was " + interval);
		}
		return interval;
	}

	@Override
	public String toString ( ) {
		return mode == Mode.SUMMARIZED
				? "summarized every " + interval
				: summarizedMethods.isEmpty() ? "per call" : "per call, summarizing " + summarizedMethods + " every " + interval;
	}

}
