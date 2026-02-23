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
	 *
	 * @param todoItem the todo item to process
	 * @param context the automation context providing command execution and event capabilities
	 * @return reference to the last event generated during processing, or empty if no events were generated
	 */
	Optional<EventReference> handle ( TODO_ITEM_TYPE todoItem, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context );

}