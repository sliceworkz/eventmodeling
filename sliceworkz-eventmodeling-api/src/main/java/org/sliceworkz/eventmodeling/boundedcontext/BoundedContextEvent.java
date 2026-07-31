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
	 * {@code slice} identifies the originating feature slice (resolved by package convention) and may
	 * be {@code null}.
	 */
	record LiveModelProjected ( String boundedContext, String readModel, Metrics metrics, FeatureSlice slice ) implements BoundedContextEvent { }

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
