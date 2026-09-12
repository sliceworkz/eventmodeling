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
package org.sliceworkz.eventmodeling.management;

import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An instruction from an operator to the instances of a deployment, delivered through an event
 * stream rather than a network call.
 * <p>
 * Every instance runs its own processors, and the admin capabilities on a bounded context
 * ({@code AutomationAdminCapability}, {@code ProcessorAdminCapability}) deliberately address only
 * the instance they are called on: there is no channel by which an operator reaches a specific
 * instance from outside. What every instance <em>can</em> reach is the event store, which is also how
 * the instances already report on themselves (the {@code BoundedContextEvent}s a monitoring
 * listener appends). An instruction is the same idea in the other direction: an operator appends
 * one to the {@link #STREAM management stream}, every bounded context subscribed to that stream
 * reads it, the ones the {@link Target} names act on it through their own admin capability, and
 * each of those answers with a {@code BoundedContextEvent.InstructionHandled} on whatever the
 * monitoring listener writes to — carrying the instruction's correlation id, so an answer can be
 * matched to the instruction it answers.
 * <p>
 * <strong>An instruction is imperative, not state, and is read from the moment a context starts.</strong>
 * A context subscribes at the stream's head when it starts and never bookmarks it: a fresh process
 * must not replay every instruction ever issued, and "stop automation X" said last week is not a
 * standing rule but a thing somebody did last week. The consequence is that an instruction issued
 * while an instance is down is not seen by it, which the missing acknowledgement makes visible. An
 * operator who wants a standing rule expresses it in the deployment, not here.
 * <p>
 * <strong>Whoever can append to the management stream controls the processors.</strong> The stream
 * is the authority; nothing on the receiving side authenticates an instruction beyond the store
 * having accepted it. Protect the store, and protect whatever appends to it (a dashboard's routes,
 * an operator's script) as you would protect a shell on the instance. Every instruction carries the
 * tracing tags of whoever appended it ({@code x-actor}, {@code x-channel}), so the stream is also the
 * record of who did what.
 * <p>
 * Registered on the builder with {@code BoundedContextBuilder.management(stream)}, where the stream
 * is one over this type on a store every instance and the operator's tooling can reach — typically
 * the same store the monitoring listener appends to, since that store already has exactly that
 * reach. {@link #STREAM} is the stream id both sides agree on.
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps an instruction written by a newer
 * operator tool readable by an older instance, the same way the bounded-context events are kept
 * readable across versions; an instruction <em>type</em> the instance does not know still fails to
 * read, and is reported rather than acted on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface ManagementInstruction {

	/** The stream instructions are appended to and read from, wherever the store holding it lives. */
	EventStreamId STREAM = EventStreamId.forContext("management").withPurpose("instructions");

	/**
	 * Which instances an instruction is for.
	 *
	 * @return never {@code null}
	 */
	Target target ( );

	/**
	 * Names the instances an instruction addresses: every field that is set has to match, and a
	 * field left {@code null} (or blank) matches anything. So {@code new Target("orders", null, null,
	 * null)} reaches every instance of the {@code orders} context; adding the {@code logical} and
	 * {@code physical} names reaches the one deployed copy an operator sees on a dashboard, whatever
	 * process happens to be running it; and {@code process} pins one JVM run, for the case where two
	 * processes are wrongly running the same instance.
	 * <p>
	 * The names are the ones every event carries in its tracing tags ({@code x-instance-logical},
	 * {@code x-instance-physical}, {@code x-instance-process}) and the ones a bounded context's
	 * lifecycle events repeat in their payload, so a target can be filled in from what an operator
	 * is looking at.
	 *
	 * @param boundedContext the bounded context's name, or {@code null} for any
	 * @param logical the instance's logical name, or {@code null} for any
	 * @param physical the instance's physical name, or {@code null} for any
	 * @param process the process id, or {@code null} for any
	 */
	record Target ( String boundedContext, String logical, String physical, String process ) {

		/** A target from its four parts, each {@code null} or blank for "any" — the general factory the named ones below are shorthands for. */
		public static Target of ( String boundedContext, String logical, String physical, String process ) {
			return new Target(boundedContext, logical, physical, process);
		}

		/** Every instance of every context: the target of an instruction meant for the whole deployment. */
		public static Target any ( ) {
			return new Target(null, null, null, null);
		}

		/** Every instance of one bounded context. */
		public static Target boundedContext ( String boundedContext ) {
			return new Target(boundedContext, null, null, null);
		}

		/** One deployed copy of a bounded context, whatever process runs it. */
		public static Target instance ( String boundedContext, String logical, String physical ) {
			return new Target(boundedContext, logical, physical, null);
		}

		/** One process running one deployed copy of a bounded context. */
		public static Target process ( String boundedContext, String logical, String physical, String process ) {
			return new Target(boundedContext, logical, physical, process);
		}

		/** Whether a bounded context with this name, running as this instance, is addressed. */
		public boolean matches ( String boundedContext, Instance instance ) {
			return matches(this.boundedContext, boundedContext)
					&& instance != null
					&& matches(logical, instance.logical())
					&& matches(physical, instance.physical())
					&& matches(process, instance.process());
		}

		private static boolean matches ( String wanted, String actual ) {
			return wanted == null || wanted.isBlank() || wanted.equals(actual);
		}
	}

	/**
	 * Stops an automation on the targeted instances, as {@code AutomationAdminCapability.stopAutomation}
	 * does: the automation hands its leadership lease back, so an instance <em>not</em> targeted takes
	 * its todo list over. Target every instance of the context to stop the automation for the whole
	 * deployment — for the maintenance window of whatever it calls, say.
	 *
	 * @param target the instances addressed
	 * @param automation the automation's id, as {@code AutomationStatus.automation} reports it
	 */
	record StopAutomation ( Target target, String automation ) implements ManagementInstruction { }

	/**
	 * Restarts a stopped automation on the targeted instances, as
	 * {@code AutomationAdminCapability.restartAutomation} does. An automation that is already running
	 * answers {@code NO_CHANGE}.
	 *
	 * @param target the instances addressed
	 * @param automation the automation's id
	 */
	record StartAutomation ( Target target, String automation ) implements ManagementInstruction { }

	/**
	 * Stops a projector-driven processor — a read model's projector, a translator, a dispatcher — on
	 * the targeted instances, as {@code ProcessorAdminCapability.stopProcessor} does. A leader-only
	 * processor hands its lease back. Stopping a {@code SHARED} read model's projector everywhere is
	 * the first step of rebuilding it: with its projector stopped, drop its tables, then start it
	 * again and it replays from the beginning.
	 *
	 * @param target the instances addressed
	 * @param kind the processor's kind
	 * @param name the processor's name, as {@code ProcessorStatus.name} reports it
	 */
	record StopProcessor ( Target target, ProcessorKind kind, String name ) implements ManagementInstruction { }

	/**
	 * Restarts a stopped processor on the targeted instances, as
	 * {@code ProcessorAdminCapability.restartProcessor} does. A running processor answers {@code NO_CHANGE}.
	 *
	 * @param target the instances addressed
	 * @param kind the processor's kind
	 * @param name the processor's name
	 */
	record StartProcessor ( Target target, ProcessorKind kind, String name ) implements ManagementInstruction { }

	/**
	 * Stops the whole bounded context on the targeted instances — every processor parked, every held
	 * lease released so the other instances take over — without terminating it, so a
	 * {@link StartBoundedContext} can bring it back. The way to drain one instance before taking it
	 * down, rather than letting its leases expire after it is killed. The context keeps listening
	 * for instructions while stopped; only terminating it ends that.
	 *
	 * @param target the instances addressed
	 */
	record StopBoundedContext ( Target target ) implements ManagementInstruction { }

	/**
	 * Starts a stopped bounded context on the targeted instances again. Starting blocks until the
	 * ephemeral read models are projected, exactly as {@code start()} does, and a context that is
	 * already started answers {@code NO_CHANGE}.
	 *
	 * @param target the instances addressed
	 */
	record StartBoundedContext ( Target target ) implements ManagementInstruction { }

	/**
	 * Asks the targeted instances to report what their automations and processors are doing right
	 * now, answered with a {@code BoundedContextEvent.InstanceStatusReported} carrying the same
	 * statuses the admin capabilities return locally. The lifecycle events say most of this already;
	 * this is for the parts they do not (a stall that has not yet produced a failure event, a
	 * standby's view of itself) and for a fresh look on demand.
	 *
	 * @param target the instances addressed
	 */
	record ReportStatus ( Target target ) implements ManagementInstruction { }

}
