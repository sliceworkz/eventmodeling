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
package org.sliceworkz.eventmodeling.readmodels;

/**
 * What a {@link CachingReadModel} has done since it was constructed: what to look at when sizing it.
 *
 * <p>The ratio of {@code hits} to {@code misses} is the whole case for the read model: every miss costs
 * what a live read of that key costs, so a cache whose reads are spread evenly over far more keys than it
 * keeps is a live model with a projector beside it, and the wrong choice.
 *
 * @param size           entries held now
 * @param maxEntries     entries the policy allows
 * @param hits           reads answered from memory
 * @param misses         loads: reads that found the key not held and loaded it from the event store
 * @param sharedLoads    reads that found the key not held while another read was loading it, and waited
 *                       for that load instead of making one of their own
 * @param evictions      entries let go because a load needed the room
 * @param expirations    entries let go because they had not been read for the policy's idle time
 * @param failedUpdates  entries let go because folding an event into them threw: the next read of the key
 *                       loads it again, and fails there if the fold still does
 * @param halted         whether the projector keeping the entries current is not running or failing:
 *                       nothing is held then, and every read loads
 */
public record CacheStatistics (
		int size,
		int maxEntries,
		long hits,
		long misses,
		long evictions,
		long expirations,
		long failedUpdates,
		long sharedLoads,
		boolean halted ) {
}
