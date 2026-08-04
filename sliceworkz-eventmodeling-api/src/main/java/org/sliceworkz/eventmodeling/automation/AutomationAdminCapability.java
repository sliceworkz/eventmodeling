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

import java.util.List;

/**
 * Reading and restarting the automations of a bounded context, for an operator rather than for the domain.
 * <p>
 * An automation only stops when it asked to, by returning {@link AutomationFailureAction#STOP_AUTOMATION}
 * from {@link Automation#onFailure} — the choice to make when a human is meant to look before any further
 * items are handled. Nothing restarts it on its own, so this is how that human's decision gets back in.
 * <p>
 * <strong>This addresses the instance it is called on, and no other.</strong> Every instance runs its own
 * processors and each keeps its own state, so an operator restarting an automation across a deployment has
 * to reach every instance. There is no remote channel here, deliberately: naming one instance is the same
 * problem leader election has to solve, and it is not solved yet.
 */
public interface AutomationAdminCapability {

	/**
	 * The automations registered on this bounded context and what they are doing, in registration order.
	 *
	 * @return one status per automation, never null, empty when none are registered
	 */
	List<AutomationStatus> automations ( );

	/**
	 * Restarts a stopped automation, so it picks its todo list up again where its bookmark left off.
	 * <p>
	 * The item it stopped on is still at the head of that list — a todo list is projected from events, so
	 * nothing was lost — which means restarting without addressing the cause handles the same item again
	 * and probably stops again. Read {@link AutomationStatus#stoppedBy} first.
	 * <p>
	 * Restarting an automation that is already running does nothing and reports {@code false}, so a
	 * double-click on a dashboard is harmless.
	 *
	 * @param automation the id from {@link AutomationStatus#automation}
	 * @return {@code true} if a stopped automation was restarted, {@code false} if it was already running
	 * @throws IllegalArgumentException if no automation with that id is registered on this context
	 */
	boolean restartAutomation ( String automation );

}
