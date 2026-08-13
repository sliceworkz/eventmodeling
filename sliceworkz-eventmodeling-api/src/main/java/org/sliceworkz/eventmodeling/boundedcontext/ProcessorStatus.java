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
 * What a projector-driven processor — a read model's projector, a translator, a dispatcher — is doing
 * on this instance, as far as an operator needs to know. The projector counterpart of
 * {@code AutomationStatus}, and a snapshot in the same sense: read without blocking the processor, so
 * the state it reports may already have moved on, which is why
 * {@link ProcessorAdminCapability#restartProcessor} reports what it actually did.
 *
 * @param kind what kind of processor this is; with {@code name} the address
 *        {@link ProcessorAdminCapability#restartProcessor} takes
 * @param name the processor's name — a read model's {@code readmodelName()}, a translator's or
 *        dispatcher's class simple name — the same string its bookmark, metric tags and the
 *        bounded-context events use
 * @param componentClass the simple name of the implementing class
 * @param storage where the component keeps its state: {@code ephemeral}, {@code local} or
 *        {@code shared}, as the lifecycle events report it. Decides whether every instance projects
 *        its own copy or a single elected leader projects for the deployment; translators and
 *        dispatchers are always {@code shared}
 * @param running whether the processor is projecting (or retrying), as opposed to retired. A
 *        processor retries transient failures itself, so {@code running} with a climbing
 *        {@code consecutiveFailedRuns} means stalled-but-alive; only a permanent failure sets it false
 * @param leader whether this instance holds the processor's leadership lease right now. A leader-only
 *        processor runs on the single elected leader, so on every other instance a running processor
 *        is a parked standby; a processor projected on every instance always reports {@code true}
 * @param consecutiveFailedRuns how many projection runs in a row have failed without progress. Zero
 *        for a processor that is getting somewhere; a number that keeps climbing is the signature of
 *        a stall — running, retrying, and getting nowhere — and resets the moment a run completes
 * @param lastFailure what escaped the projection most recently, or {@code null} if nothing has. Stays
 *        available after recovery
 * @param stoppedBy what retired the processor, or {@code null} while it is running. Kept apart from
 *        {@code lastFailure} for the same reason {@code AutomationStatus} keeps them apart: a running
 *        processor has usually survived failures, and the one an operator wants is the one that
 *        stopped it
 */
public record ProcessorStatus (
		ProcessorKind kind,
		String name,
		String componentClass,
		String storage,
		boolean running,
		boolean leader,
		int consecutiveFailedRuns,
		BoundedContextEvent.Failure lastFailure,
		BoundedContextEvent.Failure stoppedBy ) {
}
