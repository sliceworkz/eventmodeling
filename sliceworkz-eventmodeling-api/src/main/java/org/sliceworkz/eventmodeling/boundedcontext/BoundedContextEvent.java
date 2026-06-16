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

import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Events emitted by the kernel of a bounded context describing what happens inside it:
 * its lifecycle as well as the work it performs (commands executed, read models projected,
 * aggregates loaded, ...).
 * <p>
 * These events are observed through a {@link BoundedContextListener} registered on the
 * {@link BoundedContextBuilder}. The listener decides what to do with them (append to an
 * event stream, log, forward to a monitoring system, ...). When no listener is registered
 * no events are produced and there is no overhead.
 */
public sealed interface BoundedContextEvent {

	/**
	 * Emitted once, when a bounded context is built/started.
	 */
	record BoundedContextStarted (
			String boundedContext,
			String logical,
			String physical,
			String process,
			Set<FeatureSlice> enabledFeatures,
			Set<FeatureSlice> disabledFeatures ) implements BoundedContextEvent { }

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
	 * A feature slice descriptor: the slice name, its event-modeling {@link Type}, and the
	 * {@code context}/{@code chapter}/{@code tags} declared on its {@code @FeatureSlice} annotation.
	 */
	record FeatureSlice ( String name, Type type, String context, String chapter, Set<String> tags ) { }

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
