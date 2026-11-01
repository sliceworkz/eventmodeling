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

/**
 * How the leases of the leader-only processors are spread over the instances of a deployment that
 * share one {@link BoundedContextBuilder#leadershipPriority(long) priority}.
 * <p>
 * Either way every leader-only processor runs on exactly one instance at a time, a configured
 * priority still wins every lease, and nothing moves while the set of live instances stays the same.
 * The two differ in who wins a lease among instances of equal priority, and so in what happens when
 * an instance joins.
 */
public enum LeadershipDistribution {

	/**
	 * Every lease goes to the instance that weighs most for it, so the leases spread evenly over the
	 * instances. The weight is a hash of the instance's stable identity (its logical and physical
	 * name) and the lease name, so each instance wins about an equal share, the assignment is the same
	 * on every instance without any of them knowing how many others there are, and an instance that
	 * restarts under the same name wins back exactly the leases it had.
	 * <p>
	 * What it costs is a hand-over whenever the set of live instances changes: an instance that joins
	 * takes over the leases it weighs most for, about one in N, each after its holder has finished its
	 * batch. A {@link BoundedContextBuilder#leadershipStartupHoldBack(java.time.Duration) startup
	 * hold-back}, off by default, keeps instances that start together from running work on the wrong
	 * instance first.
	 */
	BALANCED,

	/**
	 * The first instance to ask for a lease keeps it until it stops, fails or a higher priority
	 * appears. Nothing ever moves to an instance that joins, so the instance that started first
	 * usually leads everything and the others stand by.
	 */
	FIRST_COME

}
