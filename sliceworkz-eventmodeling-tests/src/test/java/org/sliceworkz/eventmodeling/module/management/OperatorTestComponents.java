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
package org.sliceworkz.eventmodeling.module.management;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * The components the operator-facing tests stop and start: an automation that records what it handled
 * and completes each item with a keyed event, its event-sourced todo list, and a translator and a
 * dispatcher that do nothing — a processor of each kind, so every admin address has something behind it.
 */
final class OperatorTestComponents {

	private OperatorTestComponents ( ) { }

	/** Items arrive on {@code FirstDomainEvent} and leave again when their {@code SecondDomainEvent} is projected. */
	static class ItemsTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		ItemsTodoList ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.EPHEMERAL;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			switch ( event.data() ) {
				case FirstDomainEvent f -> items.add(f.value());
				case SecondDomainEvent s -> items.remove(s.value());
				default -> { }
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			List<String> snapshot = List.copyOf(items);
			int max = (int) Math.min(snapshot.size(), limit.value());
			return IntStream.range(0, max).mapToObj(snapshot::get);
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}

		List<String> items ( ) {
			return List.copyOf(items);
		}
	}

	/** Handles every item by recording it and raising its completion under an item-derived idempotency key. */
	static class RecordingAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final ItemsTodoList todoList;
		private final List<String> handled = new CopyOnWriteArrayList<>();

		RecordingAutomation ( String todoListName ) {
			this.todoList = new ItemsTodoList(todoListName);
		}

		ItemsTodoList todoList ( ) {
			return todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			handled.add(todoItem);
			return context.event(new SecondDomainEvent(todoItem), "done-" + todoItem);
		}

		List<String> handled ( ) {
			return List.copyOf(handled);
		}
	}

	static class NoopTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// nothing to translate in these tests
		}
	}

	static class NoopDispatcher implements Dispatcher<MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			// nothing to publish in these tests
		}
	}

}
