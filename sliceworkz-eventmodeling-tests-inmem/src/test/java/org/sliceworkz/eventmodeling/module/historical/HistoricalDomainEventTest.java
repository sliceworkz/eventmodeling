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
package org.sliceworkz.eventmodeling.module.historical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.Untyped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Demonstrates how historical domain events (event upcasting) work with a BoundedContext.
 *
 * <p>Scenario: A domain originally stored events using {@link OriginalDomainEvent} types.
 * Over time, the event schema evolved to {@link CurrentDomainEvent} types with richer data.
 * Historical events are defined in {@link HistoricalDomainEvent} with {@link LegacyEvent}
 * annotations and {@link Upcast} implementations to transparently transform old events
 * to current types when read from the event store.</p>
 */
public class HistoricalDomainEventTest {

	private EventStorage eventStorage;
	private BoundedContext<CurrentDomainEvent, InboundEvent, OutboundEvent> boundedContext;

	@BeforeEach
	void setUp() {
		this.eventStorage = createEventStorage();
	}

	@AfterEach
	void tearDown() {
		if (boundedContext != null) {
			boundedContext.stop();
		}
		destroyEventStorage(eventStorage);
	}

	public EventStorage createEventStorage() {
		return InMemoryEventStorage.newBuilder().build();
	}

	public void destroyEventStorage(EventStorage storage) {
	}

	@Test
	void historicalEventsAreUpcastedAndVisibleThroughReadModel() {

		// Step 1: Write events using the ORIGINAL event types (simulating legacy data)
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage);
		EventStream<OriginalDomainEvent> originalStream = eventStore.getEventStream(
				EventStreamId.forContext("test-historical").withPurpose("domain"),
				OriginalDomainEvent.class);

		originalStream.append(AppendCriteria.none(),
				Event.of(new OriginalDomainEvent.ItemAdded("widget", 5), Tags.none()));
		originalStream.append(AppendCriteria.none(),
				Event.of(new OriginalDomainEvent.ItemRemoved("widget"), Tags.none()));

		// Step 2: Build a BoundedContext with current + historical event types
		var builder = BoundedContext.newBuilder(Untyped.class)
				.eventTypes(CurrentDomainEvent.class, InboundEvent.class, OutboundEvent.class)
			.name("test-historical")
			.historicalEventTypes(HistoricalDomainEvent.class, null, null)
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(ItemCountReadModel.class).live();

		boundedContext = builder.build();
		boundedContext.start();

		// Step 3: Execute a command that adds a NEW event (using the current schema)
		boundedContext.execute(new AddItemCommand("gadget", 3, "electronics"));

		// Step 4: Read the live model — it should see both the 2 upcasted historical events
		// and the 1 new event, totaling 3 events processed
		ItemCountReadModel readModel = boundedContext.read(ItemCountReadModel.class);
		assertEquals(3, readModel.eventCount());
		assertEquals(8, readModel.totalAdded());  // 5 (upcasted ItemAdded->ItemAddedV2) + 3 (new ItemAddedV2)
	}

	@Test
	void historicalEventsAreUpcastedAndVisibleThroughCommand() {

		// Write an event using the ORIGINAL event types
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage);
		EventStream<OriginalDomainEvent> originalStream = eventStore.getEventStream(
				EventStreamId.forContext("test-historical").withPurpose("domain"),
				OriginalDomainEvent.class);

		originalStream.append(AppendCriteria.none(),
				Event.of(new OriginalDomainEvent.ItemAdded("widget", 5), Tags.none()));

		// Build BoundedContext with historical types
		var builder = BoundedContext.newBuilder(Untyped.class)
				.eventTypes(CurrentDomainEvent.class, InboundEvent.class, OutboundEvent.class)
			.name("test-historical")
			.historicalEventTypes(HistoricalDomainEvent.class, null, null)
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(ItemCountReadModel.class).live();

		boundedContext = builder.build();
		boundedContext.start();

		// Execute a command that raises a new event
		boundedContext.execute(new AddItemCommand("gadget", 10, "electronics"));

		// The read model should see both the upcasted historical ItemAdded and the new ItemAddedV2
		ItemCountReadModel readModel = boundedContext.read(ItemCountReadModel.class);
		assertEquals(2, readModel.eventCount());
		assertEquals(15, readModel.totalAdded()); // 5 (upcasted) + 10 (new)
	}

	// --- Original event types (as they were stored historically) ---

	public sealed interface OriginalDomainEvent {
		record ItemAdded(String name, int quantity) implements OriginalDomainEvent {}
		record ItemRemoved(String name) implements OriginalDomainEvent {}
	}

	// --- Current event types (the active schema) ---

	public sealed interface CurrentDomainEvent {
		record ItemAddedV2(String name, int quantity, String category) implements CurrentDomainEvent {}
		record ItemRemovedV2(String name, String reason) implements CurrentDomainEvent {}
	}

	// --- Historical event types with upcasters ---

	public sealed interface HistoricalDomainEvent {
		@LegacyEvent(upcast = ItemAddedUpcaster.class)
		record ItemAdded(String name, int quantity) implements HistoricalDomainEvent {}

		@LegacyEvent(upcast = ItemRemovedUpcaster.class)
		record ItemRemoved(String name) implements HistoricalDomainEvent {}
	}

	public static class ItemAddedUpcaster implements Upcast<HistoricalDomainEvent.ItemAdded, CurrentDomainEvent.ItemAddedV2> {
		@Override
		public List<CurrentDomainEvent.ItemAddedV2> upcast(HistoricalDomainEvent.ItemAdded historicalEvent) {
			return List.of(new CurrentDomainEvent.ItemAddedV2(historicalEvent.name(), historicalEvent.quantity(), "unknown"));
		}
		@Override
		public Set<Class<? extends CurrentDomainEvent.ItemAddedV2>> targetTypes() {
			return Set.of(CurrentDomainEvent.ItemAddedV2.class);
		}
	}

	public static class ItemRemovedUpcaster implements Upcast<HistoricalDomainEvent.ItemRemoved, CurrentDomainEvent.ItemRemovedV2> {
		@Override
		public List<CurrentDomainEvent.ItemRemovedV2> upcast(HistoricalDomainEvent.ItemRemoved historicalEvent) {
			return List.of(new CurrentDomainEvent.ItemRemovedV2(historicalEvent.name(), "no reason recorded"));
		}
		@Override
		public Set<Class<? extends CurrentDomainEvent.ItemRemovedV2>> targetTypes() {
			return Set.of(CurrentDomainEvent.ItemRemovedV2.class);
		}
	}

	// --- Inbound/Outbound stubs ---

	public sealed interface InboundEvent {
		record Placeholder() implements InboundEvent {}
	}

	public sealed interface OutboundEvent {
		record Placeholder() implements OutboundEvent {}
	}

	// --- Command ---

	public static class AddItemCommand implements Command<CurrentDomainEvent> {
		private final String name;
		private final int quantity;
		private final String category;

		public AddItemCommand(String name, int quantity, String category) {
			this.name = name;
			this.quantity = quantity;
			this.category = category;
		}

		@Override
		public CommandResult<CurrentDomainEvent, CurrentDomainEvent> execute(
				CommandContext<CurrentDomainEvent, CurrentDomainEvent> context) {
			var result = context.noDecisionModels();
			return result.raiseEvent(new CurrentDomainEvent.ItemAddedV2(name, quantity, category), Tags.none());
		}
	}

	// --- Read Model ---

	public static class ItemCountReadModel implements ReadModel<CurrentDomainEvent> {

		private int eventCount = 0;
		private int totalAdded = 0;

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when(CurrentDomainEvent event) {
			eventCount++;
			switch (event) {
				case CurrentDomainEvent.ItemAddedV2 added -> totalAdded += added.quantity();
				case CurrentDomainEvent.ItemRemovedV2 ignored -> {}
			}
		}

		public int eventCount() {
			return eventCount;
		}

		public int totalAdded() {
			return totalAdded;
		}
	}
}
