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
package org.sliceworkz.eventmodeling.module.threading;

public interface Processor extends Runnable {

	void stop ( );

	void start ( );

	void terminate ( ); // instruct the processor to finish what it's doing and exit

	/**
	 * Flips the election result for this processor — see {@link ProcessorInstanceMode}. Called by the
	 * leader elector, from its own thread, whenever this instance wins or loses the processor's
	 * lease; never on the processing path. Implementations wake their parked loop so a promotion
	 * takes effect immediately rather than after the next poll interval, and a demotion takes effect
	 * at the current batch boundary (a pass under way completes; the next pass reads the new mode).
	 * <p>
	 * Default no-op, which is correct for a processor that runs on every instance and so has no
	 * election result to hold.
	 */
	default void instanceMode ( ProcessorInstanceMode mode ) {
		// a processor untouched by leader election ignores this
	}

	/**
	 * Whether this processor has retired <em>itself</em> — a projector stopped by a projection
	 * failure, an automation whose failure handling returned {@code STOP_AUTOMATION} — as opposed to
	 * being stopped by its lifecycle. The leader elector reads this each round: a self-stopped
	 * processor does no work however the election goes, so its lease is released and not contended
	 * for until the processor is started again, letting a healthy instance take over.
	 * <p>
	 * Deliberately not "is stopped": every processor is lifecycle-{@code STOPPED} between
	 * construction and {@code start()}, which is exactly when the elector runs its synchronous first
	 * round so a leader exists before any processor's first pass. Only a stop the processor imposed
	 * on itself may cost it its lease.
	 */
	default boolean stoppedItself ( ) {
		return false;
	}

}
