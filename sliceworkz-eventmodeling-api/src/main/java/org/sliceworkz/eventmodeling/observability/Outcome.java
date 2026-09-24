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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;

/**
 * What an operation answered: what its {@link Observation.Scope} completes with. Each kind of
 * {@link Observation} is typed by the outcome it takes, so an observer never has to guess.
 * <p>
 * There are no durations here: the scope spans the operation, so the time is the observer's to measure
 * between {@code start} and {@code close}.
 */
public sealed interface Outcome {

	/**
	 * What a command execution answers: one of three things, none of them a failure.
	 */
	sealed interface CommandOutcome extends Outcome { }

	/**
	 * What an aggregate's append answers.
	 */
	sealed interface AppendResult extends Outcome { }

	/**
	 * What a read model batch ends in.
	 */
	sealed interface BatchResult extends Outcome { }

	/**
	 * What a snapshot load answers.
	 */
	sealed interface SnapshotLoadResult extends Outcome { }

	/**
	 * An operation with nothing to report but its completion.
	 */
	record Done ( ) implements Outcome {

		/** The one instance there is any need for. */
		public static final Done INSTANCE = new Done();

	}

	/**
	 * The command decided and what it raised was appended.
	 *
	 * @param raisedPerType how many events of each type the command raised
	 * @param appended the references of the events stored — empty when the command raised nothing, and when
	 *                 every event it raised carried an idempotency key stored before: a retry, swallowed whole
	 */
	record Executed ( Map<EventType, Integer> raisedPerType, List<EventReference> appended ) implements CommandOutcome {
		public Executed {
			raisedPerType = Map.copyOf(raisedPerType);
			appended = List.copyOf(appended);
		}
	}

	/**
	 * New facts matched the consistency boundary after the reference decided on: nothing was stored, and the
	 * caller receives an {@code OptimisticLockingException} to re-decide on. The DCB answer to a stale
	 * decision, reported as an answer rather than a failure.
	 *
	 * @param expected the reference decided on, empty for an empty boundary
	 */
	record Conflicted ( Optional<EventReference> expected ) implements CommandOutcome, AppendResult { }

	/**
	 * The command refused on a business rule, with a {@code BusinessException}: nothing was stored.
	 *
	 * @param reason the rule's message
	 */
	record Rejected ( String reason ) implements CommandOutcome { }

	/**
	 * An aggregate's events were stored.
	 *
	 * @param appended the references of the events stored
	 */
	record Appended ( List<EventReference> appended ) implements AppendResult {
		public Appended {
			appended = List.copyOf(appended);
		}
	}

	/**
	 * A provided event was appended, or found stored before.
	 *
	 * @param reference the event stored, empty when its idempotency key was stored before
	 */
	record Provided ( Optional<EventReference> reference ) implements Outcome { }

	/**
	 * An inbound event was translated interactively.
	 *
	 * @param raised the references of the domain events the translators raised, in order
	 */
	record Translated ( List<EventReference> raised ) implements Outcome {
		public Translated {
			raised = List.copyOf(raised);
		}
	}

	/**
	 * A read model batch was committed.
	 *
	 * @param eventsHandled how many events were handed to the read model
	 * @param last the last event of the batch, if the projector reported one
	 */
	record Projected ( int eventsHandled, Optional<EventReference> last ) implements BatchResult { }

	/**
	 * A read model batch was cancelled: the projection failed part-way and the batch will be offered again.
	 * The failure itself is reported on the processor's lifecycle events.
	 */
	record Cancelled ( ) implements BatchResult { }

	/**
	 * A live read model was projected.
	 *
	 * @param eventsStreamed how many events the projection read
	 * @param eventsHandled how many of them were handed to the read model
	 * @param startedAfter the base the projection started from — a seed or a snapshot — empty for a full
	 *                     replay; a seeded read model reporting empty here is one whose seed found nothing
	 * @param until the last event projected, if any
	 */
	record LiveModelProjected ( long eventsStreamed, long eventsHandled, Optional<EventReference> startedAfter, Optional<EventReference> until ) implements Outcome { }

	/**
	 * An aggregate was loaded.
	 *
	 * @param eventsStreamed how many events were replayed onto it
	 * @param startedAfter the snapshot it was restored from, empty for a full replay
	 * @param until the last event it reflects, if any
	 */
	record AggregateLoaded ( long eventsStreamed, Optional<EventReference> startedAfter, Optional<EventReference> until ) implements Outcome { }

	/**
	 * A snapshot was found.
	 *
	 * @param at the last event the snapshot reflects
	 */
	record SnapshotFound ( EventReference at ) implements SnapshotLoadResult { }

	/**
	 * No snapshot was found. The reason is what makes a bumped snapshot version visible: without it, a full
	 * replay on every read is indistinguishable from a key that was never snapshotted.
	 *
	 * @param reason why, as the snapshot storage classifies it; {@link MissReason#UNKNOWN} when it cannot
	 *               say, or when its classification threw
	 */
	record SnapshotMissed ( MissReason reason ) implements SnapshotLoadResult { }

	/**
	 * An automation batch ran.
	 *
	 * @param itemsStreamed how many todo items were pulled
	 * @param itemsHandled how many were handled
	 * @param itemsFailed how many failed, each routed through the automation's {@code onFailure}
	 * @param lastProduced the last event the batch raised, if any
	 */
	record AutomationRan ( long itemsStreamed, long itemsHandled, long itemsFailed, Optional<EventReference> lastProduced ) implements Outcome { }

}
