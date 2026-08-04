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

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Represents an automation process that handles todo items from a todo list.
 * <p>
 * Automations follow the Event Modeling automation pattern: events populate a todo list
 * read model, and the automation processes items from that list by executing commands or
 * providing events through the supplied context.
 *
 * @param <TODO_ITEM_TYPE> the type of items in the todo list
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events that can be published
 */
public interface Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	/** The number of todo items handled in one batch when an automation does not choose one. */
	int DEFAULT_BATCH_SIZE = 50;

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

}