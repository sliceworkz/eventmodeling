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
import java.util.Set;

import org.sliceworkz.eventmodeling.slices.Aspect;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventstore.events.EventReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * Events emitted by the kernel of a bounded context describing what happens inside it:
 * its lifecycle as well as the work it performs (commands executed, read models projected,
 * aggregates loaded, ...).
 * <p>
 * These events are observed through a {@link BoundedContextListener} registered on the
 * {@link BoundedContextBuilder}. The listener decides what to do with them (append to an
 * event stream, log, forward to a monitoring system, ...). When no listener is registered
 * no events are produced and there is no overhead.
 *
 * <h2>Reading back what an older version wrote</h2>
 * A listener that persists these events produces a stream that outlives the framework version that
 * wrote it, and that is typically read by a different process (a monitoring dashboard) running a
 * version of its own. These records therefore evolve: {@code BoundedContextStarted} used to carry the
 * feature slice inventory that {@link BoundedContextStarting} carries now.
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} - inherited by every record below - makes a
 * stored event whose payload has properties these records no longer declare deserialize instead of
 * being rejected by the event store's (deliberately strict) deserializer; the dropped properties are
 * ignored and a property added since reads as null. Only the payload shape is covered: an event type
 * added after the reader was built still has no record to bind to, and fails.
 * <p>
 * A property added as a <em>primitive</em> needs one thing more: null cannot bind onto it, so the
 * stored event would be rejected despite the annotation above. Such a component carries
 * {@code @JsonSetter(nulls = Nulls.AS_EMPTY)} to read as its zero value instead — see
 * {@link BoundedContextStarted#startupDurationMs()}. Prefer a boxed type where "absent" and "zero"
 * mean different things to a reader.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface BoundedContextEvent {

	/**
	 * Emitted when a bounded context begins starting up, before its modules are started. It announces
	 * what is being started: the feature slices that are deployed and the ones that are not, and which
	 * {@link Aspect}s of them this deployment runs.
	 * <p>
	 * The two are separate axes. The slice sets say <em>which</em> slices this instance carries; the
	 * aspects say <em>which parts</em> of them it runs, so a component of a deployed slice still only
	 * runs here when its aspect is among these. {@code aspects} is {@code null} on an event written
	 * before deployments announced them - meaning unknown, not "none".
	 * <p>
	 * The context is not usable yet at this point. What happens between this event and the
	 * {@link BoundedContextStarted} that follows it is the startup work — most notably projecting the
	 * ephemeral read models, which {@code start()} waits for.
	 */
	record BoundedContextStarting (
			String boundedContext,
			String logical,
			String physical,
			String process,
			Set<FeatureSlice> enabledFeatures,
			Set<FeatureSlice> disabledFeatures,
			Set<Aspect> aspects ) implements BoundedContextEvent {

		public BoundedContextStarting {
			// null is kept, and means "written before a deployment announced its aspects" - which is not
			// the same as an instance that runs none of them, so it must not be normalized away.
			aspects = aspects == null ? null : Set.copyOf(aspects);
		}
	}

	/**
	 * Emitted when a bounded context has started: its modules are running and its ephemeral read
	 * models have been projected, so it is effectively available.
	 * <p>
	 * {@code startupDurationMs} is the wall-clock time spent in {@code start()}, i.e. the delay
	 * between the preceding {@link BoundedContextStarting} and this event. Note that startup
	 * continues (with a warning) when an ephemeral read model does not finish projecting within its
	 * timeout, so a large duration paired with such a warning means the context came up before all of
	 * its read models were complete.
	 * <p>
	 * {@code @JsonSetter(nulls = AS_EMPTY)} is what makes it read as {@code 0} on an event stored
	 * before this property existed. Without it such an event fails to deserialize altogether: an
	 * absent property binds as null, and the store's deserializer cannot map null onto a primitive.
	 * Note that the {@code 0} it then reads is indistinguishable from a startup that really took no
	 * measurable time — pair it with the presence of a {@link BoundedContextStarting} to tell a
	 * pre-split event apart from a fast one.
	 */
	record BoundedContextStarted (
			String boundedContext,
			String logical,
			String physical,
			String process,
			@JsonSetter(nulls = Nulls.AS_EMPTY) long startupDurationMs ) implements BoundedContextEvent { }

	/**
	 * Emitted when a bounded context begins shutting down, before its modules are stopped (while the
	 * context and its listener are still fully operational).
	 */
	record BoundedContextStopping (
			String boundedContext,
			String logical,
			String physical,
			String process ) implements BoundedContextEvent { }

	/**
	 * Emitted when a bounded context has shut down: its modules and processor threads have stopped.
	 */
	record BoundedContextStopped (
			String boundedContext,
			String logical,
			String physical,
			String process ) implements BoundedContextEvent { }

	/**
	 * Emitted after a command has been executed successfully and its events persisted.
	 * <p>
	 * Failures are reported separately: an optimistic-locking conflict on append produces a
	 * {@link CommandFailedOnOptimisticLocking}, any other exception a {@link CommandFailed}.
	 * <p>
	 * {@code slice} identifies the feature slice the command belongs to (resolved by package
	 * convention) and is {@code null} when the command is not located within a known slice package.
	 */
	record CommandExecuted ( String boundedContext, String command, List<EventReference> raisedEvents, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted when a command execution failed because of an optimistic-locking conflict on append:
	 * relevant events were appended concurrently after the command made its decision. This is an
	 * expected, retryable outcome of the Dynamic Consistency Boundary (DCB) pattern under contention,
	 * not a defect, which is why it is reported separately from {@link CommandFailed}.
	 * <p>
	 * {@code expectedLastEvent} is the reference the command expected to still be the last relevant
	 * event when it appended; it is {@code null} when the command expected an empty stream (no matching
	 * events) but found some. The exception is rethrown to the caller after this event is emitted.
	 * <p>
	 * {@code slice} identifies the feature slice the command belongs to (resolved by package
	 * convention) and is {@code null} when the command is not located within a known slice package.
	 */
	record CommandFailedOnOptimisticLocking ( String boundedContext, String command, EventReference expectedLastEvent, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted when a command execution failed with an exception other than an optimistic-locking
	 * conflict (validation errors, infrastructure failures, bugs, ...). The exception is rethrown to
	 * the caller after this event is emitted.
	 * <p>
	 * {@code failure} captures the exception in a serialization-friendly form (type, message and
	 * rendered stack trace) so a listener can persist or forward it.
	 * <p>
	 * {@code slice} identifies the feature slice the command belongs to (resolved by package
	 * convention) and is {@code null} when the command is not located within a known slice package.
	 */
	record CommandFailed ( String boundedContext, String command, Failure failure, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted for each decision model projected while executing a command, before the
	 * {@link CommandExecuted} event of that command.
	 * <p>
	 * {@code metrics.eventsStreamed()} is the total number of events streamed across all of the
	 * command's decision-model projections (the unified/merged read), so it is identical on every
	 * {@code DecisionModelProjected} of a single command execution; {@code metrics.eventsHandled()}
	 * is the number of those events handled by (i.e. relevant to) this particular decision model.
	 * <p>
	 * {@code slice} identifies the originating feature slice (resolved by package convention) and is
	 * {@code null} when the decision model is not located within a known slice package.
	 */
	record DecisionModelProjected ( String boundedContext, String decisionModel, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted after a live (on-demand) model has been projected.
	 * <p>
	 * {@code seededAt} is the position the projection started from — the base a
	 * {@link org.sliceworkz.eventmodeling.readmodels.SeededReadModel} loaded, or a snapshot's last
	 * event — and is {@code null} for the full replay an ordinary live model does. It is here because
	 * a seed that returns empty by mistake produces a correct answer at the cost of the whole event
	 * history, which is otherwise visible only as a read that is unaccountably slow: a
	 * {@code LiveModelProjected} carrying no {@code seededAt} for a read model that is supposed to
	 * have one says so directly, and {@code metrics.eventsStreamed()} says what it cost.
	 *
	 * @param boundedContext the context the read model belongs to
	 * @param readModel the read model's name
	 * @param metrics what the projection cost, {@code eventsStreamed} included
	 * @param seededAt the position the projection started from, or {@code null} for a full replay
	 * @param slice the originating feature slice (resolved by package convention), may be {@code null}
	 */
	record LiveModelProjected ( String boundedContext, String readModel, Metrics metrics, EventReference seededAt, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted after an aggregate has been loaded from its event stream.
	 * <p>
	 * {@code slice} identifies the originating feature slice (resolved by package convention) and is
	 * {@code null} when the aggregate is not defined within a known slice package.
	 */
	record AggregateLoaded ( String boundedContext, String aggregate, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted after a batch of events has been applied to an eventually consistent read model.
	 * <p>
	 * {@code slice} identifies the originating feature slice (resolved by package convention) and may
	 * be {@code null}.
	 */
	record EventuallyConsistentReadModelUpdated ( String boundedContext, String readModel, String readModelType, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted after an automation has processed a batch of todo items.
	 * <p>
	 * {@code slice} identifies the originating feature slice (resolved by package convention) and may
	 * be {@code null}.
	 */
	record AutomationProcessed ( String boundedContext, String automation, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted when an automation completes a batch that failed and handled nothing — it is running,
	 * retrying, and getting nowhere.
	 * <p>
	 * <strong>Once per such batch, not once per failed item.</strong> A failing item is the automation's
	 * own business and it already decides what to do about it in {@code onFailure}, where it can record
	 * whatever domain event the failure deserves; emitting one of these per item would duplicate that and
	 * turn an outage into a flood. What the automation cannot report from there, and what infrastructure
	 * actually needs, is the thing only the processor can see: no progress at all. A batch that handled
	 * even one item is progress and emits nothing, however many other items failed in it.
	 * <p>
	 * The volume is bounded by the backoff, since a batch is the unit: while a dependency is down these
	 * arrive on the schedule {@code Automation.delayBeforeNextBatch} sets, which by default slows from
	 * every 10 seconds to every 5 minutes.
	 * <p>
	 * {@code consecutiveFailedBatches} is what a consumer should key its policy on rather than reacting
	 * to the first one — the framework deliberately picks no alerting threshold, because how many failed
	 * batches are worth waking somebody for is a property of what the automation talks to. There is no
	 * matching "recovered" event: an automation that gets going again emits {@link AutomationProcessed}
	 * with a non-zero {@code eventsHandled}, which is the same signal from the other side.
	 *
	 * @param boundedContext the context the automation belongs to
	 * @param automation the automation's id, the same one the metric tags use
	 * @param failure the last throwable to escape the handler in this batch
	 * @param consecutiveFailedBatches how many batches in a row have now failed without handling anything,
	 *        1 for the first
	 * @param itemsFailed how many items failed in this batch
	 * @param slice the originating feature slice (resolved by package convention), may be {@code null}
	 */
	record AutomationFailed ( String boundedContext, String automation, Failure failure, int consecutiveFailedBatches, long itemsFailed, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted when an automation has stopped and will not process any further todo items until something
	 * restarts it.
	 * <p>
	 * This is not the ordinary answer to a failing item — a failure is contained per item and the batch
	 * carries on (see {@code AutomationFailureAction}). It is emitted only where the automation itself
	 * asked for it by returning {@code STOP_AUTOMATION}, which is the choice to be made when a human is
	 * meant to look before any more items are handled. Nothing restarts the processor on its own.
	 * <p>
	 * The outstanding work is not lost: a todo list is projected from events, so every item the automation
	 * had left is still there, and the one it failed on is still at the head of it. Restarting without
	 * addressing that item means handling it again, and probably stopping again.
	 *
	 * @param boundedContext the context the automation belongs to
	 * @param automation the automation's id, the same one {@code AutomationStatus} and the metric tags use
	 * @param failure what escaped the handler
	 * @param slice the originating feature slice (resolved by package convention), may be {@code null}
	 */
	record AutomationStopped ( String boundedContext, String automation, Failure failure, FeatureSlice slice ) implements BoundedContextEvent { }

	/**
	 * Emitted when an automation begins processing todo items: once per automation when the bounded
	 * context starts, and again whenever a stopped one is restarted.
	 * <p>
	 * Emitted on the ordinary path and not only on the interesting one, so that the running automations
	 * are visible from startup rather than from whenever each of them first has work — an automation with
	 * an empty todo list would otherwise announce nothing at all, and be indistinguishable from one that
	 * is not there.
	 * <p>
	 * With {@link AutomationStopped} this is the pair to fold to answer "is it running": the later of the
	 * two wins. Note that they are deliberately not symmetric at shutdown — an automation stopping because
	 * its context is going down raises no {@code AutomationStopped}, since {@link BoundedContextStopping}
	 * already says so for all of them at once. {@code AutomationStopped} means one automation is down
	 * while its context is up, which is the state worth alerting on.
	 *
	 * @param boundedContext the context the automation belongs to
	 * @param automation the automation's id, the same one the metric tags use
	 * @param reason whether the bounded context started it or an operator restarted it
	 * @param slice the originating feature slice (resolved by package convention), may be {@code null}
	 */
	record AutomationStarted ( String boundedContext, String automation, AutomationStartReason reason, FeatureSlice slice ) implements BoundedContextEvent { }

	/** Why an {@link AutomationStarted} was raised. */
	enum AutomationStartReason {

		/** The bounded context was started, which starts every automation registered on it. */
		BOUNDED_CONTEXT_START,

		/**
		 * A stopped automation was restarted on its own, through
		 * {@code AutomationAdminCapability.restartAutomation} — so somebody decided the reason it stopped
		 * has been dealt with.
		 */
		RESTART
	}

	/**
	 * Emitted when this instance wins the leadership lease of a leader-only processor — an automation,
	 * a SHARED read model's projector, a translator or a dispatcher — and begins processing for the
	 * whole deployment. Emitted per processor, since leases are per processor: an instance only
	 * contends for the elements it has deployed, so leadership of different processors can legitimately
	 * sit on different instances.
	 * <p>
	 * On a storage without lease support (no leader election), every leader-only processor is promoted
	 * at startup with a fencing token of {@code 0}, and this event still says so.
	 *
	 * @param boundedContext the context the processor belongs to
	 * @param processorType the kind of processor: {@code readmodel}, {@code automation},
	 *        {@code translator} or {@code dispatcher}
	 * @param processor the processor's id, the same string the metric tags and bookmark reader use
	 * @param fencingToken the lease's fencing token for this tenure; strictly increases per ownership
	 *        change, {@code 0} when the storage does not support leases
	 */
	record LeadershipAcquired ( String boundedContext, String processorType, String processor, long fencingToken ) implements BoundedContextEvent { }

	/**
	 * Emitted when this instance gives up — or discovers it has lost — the leadership lease of a
	 * leader-only processor while its bounded context is up. The processor parks as a standby; some
	 * other instance takes over, immediately on a graceful hand-over and within the lease's
	 * time-to-live otherwise.
	 * <p>
	 * Deliberately not emitted at shutdown, mirroring {@link AutomationStopped}: leadership released
	 * because the context is going down is already said by {@link BoundedContextStopping} for
	 * everything at once, which keeps this event meaning the one state worth watching — leadership
	 * moved while the instance stayed up.
	 *
	 * @param boundedContext the context the processor belongs to
	 * @param processorType the kind of processor, as on {@link LeadershipAcquired}
	 * @param processor the processor's id
	 * @param reason why leadership ended here — see {@link LeadershipReleaseReason}
	 */
	record LeadershipReleased ( String boundedContext, String processorType, String processor, LeadershipReleaseReason reason ) implements BoundedContextEvent { }

	/** Why a {@link LeadershipReleased} was raised. */
	enum LeadershipReleaseReason {

		/**
		 * A contender with a strictly higher priority turned up, and this instance honoured the
		 * step-down request: it finished its current batch and handed the lease over. The ordinary
		 * fail-back path when a preferred instance returns.
		 */
		STEPPED_DOWN,

		/**
		 * The storage reported another owner holding the lease. This instance had already lost it —
		 * typically after a pause long past the lease's time-to-live — and demoted itself on finding
		 * out.
		 */
		LOST,

		/**
		 * Renewals kept failing and the time-to-live elapsed without one confirmed, so this instance
		 * demoted itself rather than assume a leadership it can no longer prove. The lease may still
		 * name it as owner; it re-acquires on the first renewal that gets through, if nobody took over.
		 */
		RENEWAL_FAILED,

		/** The bounded context was stopped on this instance, releasing its leases for others to take. */
		STOPPED,

		/**
		 * The processor stopped itself while its bounded context stayed up — a projector retired by a
		 * projection failure, an automation whose failure handling returned {@code STOP_AUTOMATION} —
		 * so this instance released the lease rather than keep renewing it for work it will not do,
		 * and a healthy instance is free to take over. This instance contends for the lease again once
		 * the processor is started (an automation's {@code restartAutomation}, or the context
		 * restarting), so a restart here resumes on the next election round if nobody took over.
		 */
		PROCESSOR_STOPPED
	}

	/*
	 * Value objects used by the events above
	 */

	/**
	 * A feature slice descriptor: the slice name, its event-modeling {@link Type}, the
	 * {@code context}/{@code chapter}/{@code tags} declared on its {@code @FeatureSlice} annotation,
	 * and the components the slice registered on the bounded context.
	 * <p>
	 * {@code members} is what the slice declares while the bounded context is built: the commands, read
	 * models, automations, translators, dispatchers and aggregates it registers in its
	 * {@code configure...} methods. An undeployed slice is never configured and therefore declares no
	 * members at all.
	 * <p>
	 * Registering a command is purely declarative: a command is executed ad hoc and attributed to a
	 * slice by package convention either way. A slice that does not register its commands still
	 * reports them, but only from their first {@link CommandExecuted} onwards.
	 * <p>
	 * An event stored before this property existed reads it as {@code null}; the compact constructor
	 * normalizes that to an empty set so readers never have to null-check it.
	 */
	record FeatureSlice ( String name, Type type, String context, String chapter, Set<String> tags, Set<SliceMember> members ) {

		public FeatureSlice {
			members = members == null ? Set.of() : Set.copyOf(members);
		}
	}

	/**
	 * A component a feature slice registers on its bounded context, named the way the events
	 * reporting its work name it (e.g. a read model by its {@code readmodelName()}), so a reader can
	 * match what a slice declares against what it observes.
	 * <p>
	 * {@code aspect} is the facet of the slice it was registered in, which together with the aspects a
	 * deployment announces is what says where the component actually runs. It matters because the same
	 * read model class can be registered in more than one aspect - projected on demand to answer a
	 * query on one instance, kept up to date by a projector on another - and those are different
	 * members that happen to share a name. It is {@code null} on an event written before members
	 * carried their aspect.
	 */
	record SliceMember ( String name, MemberKind kind, Aspect aspect ) { }

	/** What a {@link SliceMember} is. */
	enum MemberKind {
		COMMAND,
		READ_MODEL,
		AUTOMATION,
		TRANSLATOR,
		DISPATCHER,
		AGGREGATE
	}

	/**
	 * A serialization-friendly description of an exception raised during command execution.
	 *
	 * @param type       the fully qualified class name of the exception
	 * @param message    the exception message (may be {@code null})
	 * @param stackTrace the full rendered stack trace
	 */
	record Failure ( String type, String message, String stackTrace ) { }

	/**
	 * Performance metrics describing the work performed by an operation.
	 *
	 * @param durationMs     wall-clock duration of the operation in milliseconds
	 * @param queriesDone    number of event store queries performed
	 * @param eventsStreamed number of events streamed/replayed
	 * @param eventsHandled  number of events handled/applied
	 * @param until          reference of the last event reached (may be {@code null})
	 */
	record Metrics ( long durationMs, long queriesDone, long eventsStreamed, long eventsHandled, EventReference until ) { }

}
