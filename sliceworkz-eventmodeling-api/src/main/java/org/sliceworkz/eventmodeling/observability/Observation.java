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
package org.sliceworkz.eventmodeling.observability;

import java.util.Map;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * One operation of a bounded context, as it is about to be done: what {@link BoundedContextObserver#start}
 * is handed. Each kind is a record carrying the caller's own arguments, and is typed by the {@link Outcome}
 * its scope completes with.
 * <p>
 * Sealed, so an observer switching over the kinds with a {@code default} branch keeps compiling when one is
 * added. Where an operation runs on behalf of a caller, the observation carries the caller's
 * {@link Tracing} — actor, channel, correlation id — as it reaches the framework; where it runs per stored
 * event on a processor's thread (a read model update, a dispatch), it carries the {@link Event}, and
 * {@code Tracing.readFrom(event)} gives the flow it belongs to.
 *
 * @param <O> what the operation reports when it completes
 */
public sealed interface Observation<O extends Outcome> {

	/**
	 * The bounded context the operation runs in.
	 *
	 * @return the bounded context's name
	 */
	String boundedContext ( );

	/**
	 * Which stream a command appends to.
	 */
	enum Target {
		/** A {@code Command} or {@code CommandWithResult}: the domain stream. */
		DOMAIN,
		/** An {@code OutboundCommand}: the outbound stream. */
		OUTBOUND
	}

	/**
	 * Who holds a snapshot.
	 */
	enum SnapshotOwner {
		/** An aggregate registered with snapshots. */
		AGGREGATE,
		/** A live read model registered with snapshots. */
		LIVE_MODEL
	}

	/**
	 * One execution of a command: its decision models read, the command run, what it raised appended.
	 * {@code executeWithRetry} executes once per attempt, so each attempt is an observation of its own.
	 *
	 * @param boundedContext the bounded context
	 * @param command the command's name
	 * @param commandClass the command's class
	 * @param target the stream the command appends to
	 * @param tracing the caller's tracing, the command named on it
	 */
	record CommandExecution ( String boundedContext, String command, Class<?> commandClass, Target target, Tracing tracing ) implements Observation<Outcome.CommandOutcome> { }

	/**
	 * A domain event provided through {@code event(...)}, appended with no consistency boundary.
	 *
	 * @param boundedContext the bounded context
	 * @param eventType the event's type
	 * @param tracing the caller's tracing
	 */
	record ProvidedEvent ( String boundedContext, EventType eventType, Tracing tracing ) implements Observation<Outcome.Provided> { }

	/**
	 * An inbound event received through {@code incoming(...)} and appended to the inbound stream, for the
	 * translators to pick up asynchronously.
	 *
	 * @param boundedContext the bounded context
	 * @param eventType the inbound event's type
	 * @param tracing the caller's tracing
	 */
	record IncomingEvent ( String boundedContext, EventType eventType, Tracing tracing ) implements Observation<Outcome.Done> { }

	/**
	 * An inbound event translated interactively through {@code translate(...)}: every matching translator
	 * run on the caller's thread, each one a {@link TranslatorInvocation} nested in this scope.
	 *
	 * @param boundedContext the bounded context
	 * @param eventType the inbound event's type
	 * @param tracing the caller's tracing
	 */
	record Translation ( String boundedContext, EventType eventType, Tracing tracing ) implements Observation<Outcome.Translated> { }

	/**
	 * One translator handed one inbound event — on its processor's thread for an event that came in through
	 * {@code incoming(...)}, or on the caller's inside a {@link Translation}.
	 *
	 * @param boundedContext the bounded context
	 * @param translator the translator's name
	 * @param eventType the inbound event's type
	 * @param tracing the tracing the translator's raised events carry, continuing the inbound event's flow
	 */
	record TranslatorInvocation ( String boundedContext, String translator, EventType eventType, Tracing tracing ) implements Observation<Outcome.Done> { }

	/**
	 * One dispatcher handed one outbound event.
	 *
	 * @param boundedContext the bounded context
	 * @param dispatcher the dispatcher's name
	 * @param event the outbound event
	 */
	record Dispatch ( String boundedContext, String dispatcher, Event<?> event ) implements Observation<Outcome.Done> {

		/**
		 * @return the type of the event dispatched
		 */
		public EventType eventType ( ) {
			return event.type();
		}

	}

	/**
	 * One batch of an eventually consistent read model's projector: from the batch starting to it being
	 * committed or cancelled. Each event handed to the read model is a {@link ReadModelUpdate} nested in
	 * this scope.
	 *
	 * @param boundedContext the bounded context
	 * @param readModel the read model's name
	 * @param storage where the read model keeps its state
	 */
	record ReadModelBatch ( String boundedContext, String readModel, ReadModelStorage storage ) implements Observation<Outcome.BatchResult> { }

	/**
	 * One event handed to an eventually consistent read model.
	 *
	 * @param boundedContext the bounded context
	 * @param readModel the read model's name
	 * @param storage where the read model keeps its state
	 * @param event the event
	 */
	record ReadModelUpdate ( String boundedContext, String readModel, ReadModelStorage storage, Event<?> event ) implements Observation<Outcome.Done> {

		/**
		 * @return the type of the event handed to the read model
		 */
		public EventType eventType ( ) {
			return event.type();
		}

	}

	/**
	 * A read of a live read model: constructed, seeded or restored from a snapshot if it is either, and
	 * projected. A snapshot load or save is a {@link SnapshotLoad} or {@link SnapshotSave} nested in this
	 * scope.
	 *
	 * @param boundedContext the bounded context
	 * @param readModel the read model's name, its class's simple name
	 * @param readModelClass the read model's class
	 * @param unbounded whether it is read across every stream in the storage rather than this context's own
	 * @param tracing the caller's tracing
	 */
	record LiveModelRead ( String boundedContext, String readModel, Class<?> readModelClass, boolean unbounded, Tracing tracing ) implements Observation<Outcome.LiveModelProjected> { }

	/**
	 * A load of an aggregate: constructed, restored from a snapshot if it has one, and brought up to date
	 * from the stream. A snapshot load or save is nested in this scope.
	 *
	 * @param boundedContext the bounded context
	 * @param aggregate the aggregate's name, its class's simple name
	 * @param identity the tags identifying the instance loaded
	 * @param tracing the caller's tracing
	 */
	record AggregateLoad ( String boundedContext, String aggregate, Tags identity, Tracing tracing ) implements Observation<Outcome.AggregateLoaded> { }

	/**
	 * An append of the events an aggregate raised, checked against the aggregate's own boundary.
	 *
	 * @param boundedContext the bounded context
	 * @param aggregate the aggregate's name
	 * @param identity the tags identifying the instance
	 * @param raisedPerType how many events of each type are appended
	 * @param tracing the tracing the aggregate was loaded with
	 */
	record AggregateAppend ( String boundedContext, String aggregate, Tags identity, Map<EventType, Integer> raisedPerType, Tracing tracing ) implements Observation<Outcome.AppendResult> {
		public AggregateAppend {
			raisedPerType = Map.copyOf(raisedPerType);
		}
	}

	/**
	 * A load from a snapshot storage. What the storage itself costs, apart from the replay around it.
	 *
	 * @param boundedContext the bounded context
	 * @param owner what kind of component the snapshot belongs to
	 * @param component the component's name
	 * @param key the snapshot's key
	 * @param version the snapshot version asked for
	 */
	record SnapshotLoad ( String boundedContext, SnapshotOwner owner, String component, String key, String version ) implements Observation<Outcome.SnapshotLoadResult> { }

	/**
	 * A save to a snapshot storage.
	 *
	 * @param boundedContext the bounded context
	 * @param owner what kind of component the snapshot belongs to
	 * @param component the component's name
	 * @param key the snapshot's key
	 * @param version the snapshot version saved under
	 * @param at the last event the snapshot reflects
	 */
	record SnapshotSave ( String boundedContext, SnapshotOwner owner, String component, String key, String version, EventReference at ) implements Observation<Outcome.Done> { }

	/**
	 * One batch of an automation: todo items pulled from its todo list and handed to it one at a time, each
	 * handling's commands observed as {@link CommandExecution}s nested in this scope. A failing item is the
	 * automation's own business through {@code onFailure}, counted on the outcome; the scope fails only when
	 * the batch itself could not run.
	 *
	 * @param boundedContext the bounded context
	 * @param automation the automation's name
	 * @param tracing the tracing of the run
	 */
	record AutomationRun ( String boundedContext, String automation, Tracing tracing ) implements Observation<Outcome.AutomationRan> { }

	/**
	 * One observed operation, current on the caller's thread from {@link BoundedContextObserver#start} to
	 * {@link #close()}.
	 * <p>
	 * The framework calls exactly one of {@link #completed(Outcome)} and {@link #failed(Throwable)}, then
	 * {@link #close()} in a {@code finally}, all on the thread that started the operation.
	 *
	 * @param <O> what the operation reports when it completes
	 */
	interface Scope<O extends Outcome> extends AutoCloseable {

		/**
		 * The operation answered — for a command, that includes a conflict and a business rejection.
		 *
		 * @param outcome what the operation answered
		 */
		void completed ( O outcome );

		/**
		 * The operation could not answer. The throwable is the one the caller receives.
		 *
		 * @param failure what the operation failed with
		 */
		void failed ( Throwable failure );

		/**
		 * Ends the observation. Declared without a checked exception, unlike {@link AutoCloseable#close()}.
		 */
		@Override
		void close ( );

	}

}
