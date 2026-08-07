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

/**
 * Whether this instance is the one running a leader-only processor right now — the election result,
 * flipped at runtime by the leader elector through {@link Processor#instanceMode}, and read by every
 * loop pass. Irrelevant to a {@link ProcessorMode#RUNNING_ON_ALL_INSTANCES} processor.
 * <p>
 * Termination used to be a third value of this enum, which made "standing by and shutting down"
 * unrepresentable and let {@code terminate()} clobber the election result; it is a separate flag on
 * the processors now.
 */
public enum ProcessorInstanceMode {

	/** This instance is in charge: the processor does the work here. */
	LEADER,

	/**
	 * Another instance is handling everything; this one parks and could be elected at any moment.
	 * A leader-only processor starts here and is promoted by the elector, never by default.
	 */
	STANDBY,

}
