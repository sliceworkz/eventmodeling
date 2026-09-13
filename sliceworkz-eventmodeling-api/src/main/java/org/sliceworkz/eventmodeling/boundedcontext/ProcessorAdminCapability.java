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

import java.util.List;

/**
 * Reading and restarting the projector-driven processors of a bounded context — read model
 * projectors, translators and dispatchers — for an operator rather than for the domain. The projector
 * counterpart of {@code AutomationAdminCapability}, which stays the admin surface for automations.
 * <p>
 * A processor retries transient projection failures by itself, with backoff, so most failures never
 * need this capability: they show up as {@code ReadModelProjectorFailed} (or the translator/dispatcher
 * equivalent) while the processor keeps retrying, and clear on their own when the cause does. What
 * stops a processor for good is a <em>permanent</em> failure — a poison event, a closed storage, a
 * fenced-out leadership — and that is what this capability exists for: seeing it
 * ({@link ProcessorStatus#stoppedBy}) and, once the cause is dealt with, putting the processor back
 * without restarting the whole bounded context.
 * <p>
 * <strong>This addresses the instance it is called on, and no other.</strong> Every instance runs its
 * own processors, so an operator restarting one across a deployment has to reach every instance —
 * the same stance {@code AutomationAdminCapability} takes, for the same reason, and with the same
 * remote channel: a {@link org.sliceworkz.eventmodeling.management.ManagementInstruction} on the
 * management stream reaches the instances its target names, which then call these methods on themselves.
 */
public interface ProcessorAdminCapability {

	/**
	 * The projector-driven processors of this bounded context and what they are doing, in
	 * registration order per kind: read models, then translators, then dispatchers. Automations are
	 * not listed here — {@code AutomationAdminCapability.automations()} is theirs.
	 *
	 * @return one status per processor, never null, empty when none are registered
	 */
	List<ProcessorStatus> processors ( );

	/**
	 * Restarts a stopped processor, so it resumes projecting from where its position durably left
	 * off. Nothing was skipped by the stop — the retired projector's cursor was rolled back to the
	 * start of the batch it failed on — so nothing is skipped by the restart either.
	 * <p>
	 * Restarting without addressing the cause replays the same batch and, for a permanent cause,
	 * stops again: read {@link ProcessorStatus#stoppedBy} first. A leader-only processor resumes via
	 * re-election — within a heartbeat when nobody took its lease over — rather than instantly.
	 * <p>
	 * Restarting a processor that is already running (including one that is backing off between
	 * retries) does nothing and reports {@code false}, so a double-click on a dashboard is harmless.
	 *
	 * @param kind the processor's kind, from {@link ProcessorStatus#kind}
	 * @param name the processor's name, from {@link ProcessorStatus#name}
	 * @return {@code true} if a stopped processor was restarted, {@code false} if it was running
	 * @throws IllegalArgumentException if no processor of that kind with that name is registered on
	 *         this context; the message names the ones that are
	 */
	boolean restartProcessor ( ProcessorKind kind, String name );

	/**
	 * Stops a running processor on this instance, so it projects nothing further until something
	 * restarts it. What it is for: rebuilding a read model (stop its projector everywhere, drop its
	 * tables, start it again — a {@code SHARED} SQL read model with no bookmark row replays from the
	 * beginning), or holding a dispatcher back while the system it publishes to is down for
	 * maintenance, without the retries piling up failure events meanwhile.
	 * <p>
	 * A leader-only processor stopped this way hands its lease back, exactly as one retired by a
	 * permanent failure does, so another instance takes over unless it is stopped there too. A
	 * processor projected on every instance ({@code EPHEMERAL}, {@code LOCAL}) is simply parked here.
	 * The projector's position is untouched: a restart resumes where it durably left off, and a
	 * batch in progress is completed — or rolled back whole — before the processor parks, never cut
	 * in the middle.
	 * <p>
	 * Announced as the kind's {@code ...Stopped} event with reason {@code OPERATOR} and no failure.
	 * Stopping a processor that is already stopped does nothing and reports {@code false}.
	 *
	 * @param kind the processor's kind, from {@link ProcessorStatus#kind}
	 * @param name the processor's name, from {@link ProcessorStatus#name}
	 * @return {@code true} if a running processor was stopped, {@code false} if it was already stopped
	 * @throws IllegalArgumentException if no processor of that kind with that name is registered on
	 *         this context; the message names the ones that are
	 */
	boolean stopProcessor ( ProcessorKind kind, String name );

}
