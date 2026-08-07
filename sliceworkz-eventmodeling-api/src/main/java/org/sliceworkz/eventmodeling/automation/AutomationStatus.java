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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;

/**
 * What an automation is doing on this instance, as far as an operator needs to know.
 * <p>
 * A snapshot, read without blocking the processor, so the state it reports may already have moved on.
 * That is fine for what it is for — telling an operator which automations are stopped and why — and it
 * is why {@link AutomationAdminCapability#restartAutomation} reports what it actually did rather than
 * expecting the caller to have checked first.
 *
 * @param automation the automation's id, the same string the metric tags and the
 *        {@link BoundedContextEvent.AutomationStopped} event use
 * @param automationClass the simple name of the implementing class
 * @param running whether it is processing todo items, as opposed to stopped
 * @param leader whether this instance holds the automation's leadership lease right now. An automation
 *        runs on the single elected leader, so on every other instance a running automation is a parked
 *        standby: {@code running} says it would process if elected, {@code leader} says it actually is.
 *        Restarting a stopped automation on a standby instance puts it back to standing by, not to work
 * @param itemsFailed how many todo items have failed on this instance since it started, whatever the
 *        automation decided to do about them
 * @param consecutiveFailedBatches how many batches in a row have failed without handling anything. Zero
 *        for an automation that is getting somewhere; a number that keeps climbing is the signature of a
 *        stall — running, retrying, and making no progress — which {@code itemsFailed} alone cannot show,
 *        since a healthy automation accumulates failures too
 * @param lastFailure what escaped the handler most recently, or {@code null} if nothing has
 * @param stoppedBy what escaped the handler on the failure that stopped it, or {@code null} if it is
 *        running. Kept apart from {@code lastFailure} because a running automation has usually survived
 *        failures, and the one that stopped it is the one an operator is looking for
 */
public record AutomationStatus (
		String automation,
		String automationClass,
		boolean running,
		boolean leader,
		long itemsFailed,
		int consecutiveFailedBatches,
		BoundedContextEvent.Failure lastFailure,
		BoundedContextEvent.Failure stoppedBy ) {
}
