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
package org.sliceworkz.eventmodeling.automation;

/**
 * What a business rejection of the command a {@link Policy} issued does — a {@code BusinessException}
 * thrown by the command, a rule it judged as violated included.
 * <p>
 * Any other failure always stalls a policy; a rejection is the one failure that may also be an ordinary
 * answer, so the registration says which it is.
 */
public enum PolicyRejectionHandling {

	/**
	 * The policy stalls on the rejected event: it retries it with backoff, and every domain event behind it
	 * waits, reported as {@code PolicyFailed} until the rejection clears — because the state the command
	 * decides on changed, or an operator skipped the event. The choice where a rejection means something is
	 * wrong and has to be obvious.
	 */
	STALL,

	/**
	 * The rejection is the command's answer: reported as {@code PolicyEventSkipped} (and as the command's own
	 * {@code CommandRejected}), and the policy moves on to the next event.
	 */
	SKIP

}
