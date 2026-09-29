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

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.ports.PortCaller;

/**
 * Which component's code is running on this thread, for a monitored port to say who called it.
 * <p>
 * A port proxy sees a thread and nothing else, so the framework marks every point it hands control to user
 * code — a command's {@code execute}, an automation's {@code handle}/{@code onFailure}, a translator, a
 * dispatcher, a read model's {@code when}, a live model read — with the component, its class (which is how
 * its feature slice is found) and its tracing. Scopes nest: a live model read inside a command is the read
 * model's for its duration and the command's again after it, which is what {@link Scope#close()} restores.
 * A virtual thread carries its own value, like any thread; nothing is inherited by a thread the component
 * starts itself, whose calls are {@link PortCaller#UNATTRIBUTED}.
 */
public final class PortCallerScope {

	/**
	 * What is running.
	 *
	 * @param caller the component
	 * @param componentClass its class, for resolving its feature slice
	 * @param tracing its tracing, {@code null} when it has none
	 */
	public record Current ( PortCaller caller, Class<?> componentClass, Tracing tracing ) { }

	private static final Current NONE = new Current(PortCaller.UNATTRIBUTED, null, null);

	private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

	private PortCallerScope ( ) { }

	/** Restores what was running before, when the component returns. */
	public interface Scope extends AutoCloseable {
		@Override
		void close ( );
	}

	/**
	 * Marks the calling thread as running the given component until the returned scope is closed.
	 */
	public static Scope enter ( PortCaller caller, Class<?> componentClass, Tracing tracing ) {
		Current previous = CURRENT.get();
		CURRENT.set(new Current(caller, componentClass, tracing));
		return ( ) -> {
			if ( previous == null ) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		};
	}

	/**
	 * @return what is running on this thread; {@link PortCaller#UNATTRIBUTED} with no class or tracing
	 *         outside every component
	 */
	public static Current current ( ) {
		Current current = CURRENT.get();
		return current == null ? NONE : current;
	}

}
