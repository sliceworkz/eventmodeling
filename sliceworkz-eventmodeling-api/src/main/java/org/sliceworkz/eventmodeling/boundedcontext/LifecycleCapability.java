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
package org.sliceworkz.eventmodeling.boundedcontext;

public interface LifecycleCapability {

	// TODO guard against duplicate start() or stop().  remark: only start() is currenlty used to delay processor threads from starting until boundedcontext is initialized and available from code.
	
	void start ( );

	void stop ( );

	/**
	 * Shuts this down for good: stops the processors, drains their threads, and releases what it
	 * created — for a bounded context, the {@code EventStore} it built over the storage it was given.
	 * <p>
	 * Terminal, and idempotent. The event storage is <em>not</em> closed: it was supplied from outside,
	 * it can back other bounded contexts, and it usually outlives this one. Close it yourself after
	 * terminating every context on it (see
	 * {@link BoundedContextBuilder#eventStorage(org.sliceworkz.eventstore.spi.EventStorage)}).
	 * <p>
	 * A bounded context also terminates itself from a JVM shutdown hook, so a process that simply
	 * exits does not strand its threads; calling this explicitly is how a test or an application that
	 * outlives the context releases them earlier.
	 */
	void terminate ( );
	
}
