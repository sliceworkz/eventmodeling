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

import java.time.Duration;
import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Represents an automation process that handles todo items from a todo list.
 * <p>
 * Automations follow the Event Modeling automation pattern: events populate a todo list
 * read model, and the automation processes items from that list by executing commands or
 * providing events through the supplied context.
 * <p>
 * <strong>One automation is sequential by construction, and parallelism is realized across
 * automations.</strong> An automation runs on a single processor thread, on the single elected leader
 * of the deployment, and the next item is only handed to {@link #handle} once the current one has
 * returned — that is what makes the order {@link TodoListReadModel#streamItems} defines mean anything,
 * so there is deliberately no concurrency setting to turn up. Where one queue's throughput is not
 * enough, partition the work over several automations: each is its own class (an automation's name is
 * its class' simple name, and names key bookmarks and leases, so every automation needs its own), with
 * its own todo list projecting a disjoint share of the items — one todo list class can serve all of
 * them under different names via
 * {@link org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData#readmodelName()}. Each then
 * processes on its own thread, under its own lease, so the partitions proceed in parallel and a
 * deployment may even spread them over different instances. The partition must be stable — an item,
 * and everything that must stay ordered with it (derive the partition from something like a customer
 * or account id, not from arrival order), always landing in the same automation's todo list — because
 * ordering holds within one automation and nowhere else.
 *
 * @param <TODO_ITEM_TYPE> the type of items in the todo list
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events that can be published
 */
public interface Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	/** The number of todo items handled in one batch when an automation does not choose one. */
	int DEFAULT_BATCH_SIZE = 50;

	/** How long an automation with nothing to do waits before looking at its todo list again. */
	Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);

	/** How far the default backoff grows while an automation keeps failing to get anywhere. */
	Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(5);

	/**
	 * Returns the todo list read model that provides items for this automation to process.
	 *
	 * @return the todo list read model
	 */
	TodoListReadModel<DOMAIN_EVENT_TYPE, TODO_ITEM_TYPE> getTodoList ( );

	/**
	 * Handles a single todo item from the todo list.
	 * <p>
	 * The automation processes the item by executing commands or providing events through
	 * the supplied context. Processing should be idempotent to handle retries.
	 * <p>
	 * Delivery is at least once: an item is handled again whenever the events it raised have not
	 * reached the todo list, which is the case after a crash between the append and the bookmark, and
	 * after every failure classified by {@link #onFailure}. Give the raised events an idempotency key
	 * (see {@link org.sliceworkz.eventmodeling.commands.CommandResult#idempotencyKey}) wherever handling
	 * an item twice would be wrong.
	 *
	 * @param todoItem the todo item to process
	 * @param context the automation context providing command execution and event capabilities
	 * @return reference to the last event generated during processing, or empty if no events were generated
	 */
	Optional<EventReference> handle ( TODO_ITEM_TYPE todoItem, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context );

	/**
	 * How many todo items are handled before the automation waits for its todo list to catch up again.
	 * <p>
	 * Within a batch the todo list does not move: it is projected by its own processor, on its own
	 * thread, and the events raised while handling an item reach it afterwards. So the items behind the
	 * one being handled are decided on state that item may already have invalidated.
	 * <p>
	 * That is what this controls. Between batches the processor bookmarks the last event it produced and
	 * does not start the next batch until the todo list has been projected past it, so a batch size of
	 * {@code 1} gives each item a todo list that accounts for every event the previous item raised — the
	 * setting to choose when handling one item can cancel or supersede the ones behind it. The cost is a
	 * projection round trip per item. The default of {@value #DEFAULT_BATCH_SIZE} is the other end: it
	 * amortises that round trip over a whole batch and suits items that are independent of each other.
	 * <p>
	 * A todo list can also anticipate its own projection to get the same effect within a batch — see
	 * {@link TodoListReadModel#streamItems}.
	 *
	 * @return the maximum number of items handled per batch, must be greater than 0
	 */
	default int batchSize ( ) {
		return DEFAULT_BATCH_SIZE;
	}

	/**
	 * Decides what happens after {@link #handle} threw for a todo item.
	 * <p>
	 * The item is not lost whichever action is returned — a todo list is projected from events, so an
	 * item that was not handled is still outstanding and is offered again later. What is decided here is
	 * only what happens to the rest of the work while it waits its turn again.
	 * <p>
	 * The default is {@link AutomationFailureAction#RETRY_ITEM}: the automation keeps running, and the
	 * items behind the failing one wait for it rather than overtaking it. {@code streamItems} defines the
	 * order and the framework does not abandon that order just because something went wrong, nor can it
	 * tell whether the items behind a failing one depend on it. Where they are independent of each other,
	 * say so by returning {@link AutomationFailureAction#CONTINUE_AND_RETRY_ITEM_LATER} — that is what
	 * keeps one poison item from holding up everything behind it.
	 * <p>
	 * The other thing worth doing here is recording the failure as a domain event through {@code context},
	 * which the todo list then projects to defer or drop the item. That is the only durable form of a dead
	 * letter available: state kept anywhere other than in events is gone at the next restart, and the item
	 * comes straight back.
	 * <p>
	 * A throwable escaping this method is logged and treated as
	 * {@link AutomationFailureAction#RETRY_ITEM}.
	 *
	 * @param todoItem the item whose handling failed
	 * @param cause the throwable that escaped {@link #handle}
	 * @param context the automation context, for recording the failure as an event
	 * @return what to do with the item and with the rest of the batch, never null
	 */
	default AutomationFailureAction onFailure ( TODO_ITEM_TYPE todoItem, Throwable cause, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context ) {
		return AutomationFailureAction.RETRY_ITEM;
	}

	/**
	 * How long to wait before reading the todo list again, after a batch that got nowhere.
	 * <p>
	 * Consulted only when the processor has decided to wait at all — a batch that filled its window and
	 * moved its bookmark goes straight round without asking, and a batch that made no progress but whose
	 * todo list has changed underneath it comes back immediately, because there is new work to see.
	 * Neither of those is a pacing decision.
	 * <p>
	 * The one that is, and the reason this is an automation's own business, is the third case: a batch
	 * that <em>failed</em> and handled nothing. A todo list that moved says nothing about whether the
	 * dependency the handler needs has come back, so a failing automation is held here rather than
	 * released by the change, and how long it is held is a property of what it talks to. The default
	 * doubles from {@link #DEFAULT_POLL_INTERVAL} up to {@link #DEFAULT_MAX_BACKOFF}, so a dependency that
	 * is down for an hour costs a handful of attempts rather than one per todo-list update — which
	 * matters most under {@link AutomationFailureAction#CONTINUE_AND_RETRY_ITEM_LATER}, where every
	 * attempt is a whole batch of failing calls rather than one.
	 * <p>
	 * The cost of backing off is that recovery is noticed late: after a long outage the automation may sit
	 * out most of the current delay before trying again. Override this to trade that against the load a
	 * retry puts on whatever is down — returning {@link Duration#ZERO} makes the processor come straight
	 * back, which is only sensible when a failure is cheap to repeat.
	 *
	 * @param consecutiveFailedBatches how many batches in a row have failed without handling anything,
	 *        1 for the first, and 0 when the last batch did not fail (it simply found nothing to do)
	 * @param lastFailure the throwable from the most recent failure, or {@code null} when nothing failed
	 * @return how long to wait, never null and never negative — the processor still wakes early on
	 *         shutdown, and on a todo-list change when the batch had not failed
	 */
	default Duration delayBeforeNextBatch ( int consecutiveFailedBatches, Throwable lastFailure ) {
		if ( consecutiveFailedBatches <= 0 ) {
			return DEFAULT_POLL_INTERVAL;
		}
		int doublings = Math.min(consecutiveFailedBatches - 1, 16); // 16 is far past the cap, and cannot overflow
		long backoffMs = DEFAULT_POLL_INTERVAL.toMillis() << doublings;
		return Duration.ofMillis(Math.min(backoffMs, DEFAULT_MAX_BACKOFF.toMillis()));
	}

}