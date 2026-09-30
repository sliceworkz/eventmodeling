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

import java.util.Locale;

/**
 * How the framework measures a duration it reports: microseconds, on {@link System#nanoTime()}.
 * <p>
 * Every duration a {@code BoundedContextEvent} carries goes through here, so commands, decision models,
 * read models, automations and port calls are measured with one clock and in one unit, and compare
 * directly. The clock is the monotonic one: a wall-clock reading moves when the system clock is
 * adjusted, which turns an interval taken across the adjustment into a negative or inflated duration.
 * Deadlines and timestamps are a different matter and stay on the wall clock.
 * <p>
 * <pre>{@code
 * long started = Elapsed.start();
 * ...
 * long durationMicros = Elapsed.microsSince(started);
 * }</pre>
 */
public final class Elapsed {

	private Elapsed ( ) { }

	/**
	 * @return a starting point for {@link #microsSince(long)}; meaningful only to that method
	 */
	public static long start ( ) {
		return System.nanoTime();
	}

	/**
	 * @param started what {@link #start()} returned
	 * @return the microseconds elapsed since, never negative; an operation shorter than a microsecond
	 *         reads as {@code 0}
	 */
	public static long microsSince ( long started ) {
		return Math.max(0, (System.nanoTime() - started) / 1_000);
	}

	/**
	 * Renders a duration for a log line, in the unit that keeps it readable: {@code 850 µs},
	 * {@code 12.4 ms}, {@code 3.21 s}. The events carry the number; this is for people.
	 *
	 * @param micros a duration in microseconds
	 */
	public static String describe ( long micros ) {
		if ( micros < 1_000 ) {
			return micros + " µs";
		}
		if ( micros < 999_950 ) {
			return String.format(Locale.ROOT, "%.1f ms", micros / 1_000.0);
		}
		return String.format(Locale.ROOT, "%.2f s", micros / 1_000_000.0);
	}

}
