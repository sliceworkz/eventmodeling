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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.events.TracingScope;

/**
 * Runs work through a bounded context's capabilities under a given tracing, by binding it to the calling
 * thread for the duration of the call.
 * <p>
 * The capabilities take no tracing as an argument: a call uses the {@link TracingScope} bound to its
 * thread. A component's context — an automation's, a translator's, a policy's reaction — holds the tracing
 * of the event or item it handles, and is what binds it around each call it forwards, so everything the
 * call does runs in that flow whatever the thread had bound. The binding is restored when the call
 * returns, so it never outlives the call that needed it. It is {@link TracingScope#call(Tracing, Supplier)}
 * tolerating a component that has no tracing of its own.
 */
public final class BoundTracing {

	private BoundTracing ( ) { }

	/**
	 * Calls {@code work} with {@code tracing} bound to this thread; a {@code null} tracing binds nothing.
	 */
	public static <T> T call ( Tracing tracing, Supplier<T> work ) {
		return tracing == null ? work.get() : TracingScope.call(tracing, work);
	}

	/**
	 * Runs {@code work} with {@code tracing} bound to this thread; a {@code null} tracing binds nothing.
	 */
	public static void run ( Tracing tracing, Runnable work ) {
		call(tracing, ( ) -> { work.run(); return null; });
	}

}
