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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.CorrelatedTodoItem;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * One correlation id names one flow, reused - never re-minted - across every step: command → domain
 * event → todo list → automation → raised event, translators on both paths, and the monitoring
 * events observing it all. These scenarios pin each hop; they are framework behaviour, not storage
 * behaviour, so they are plain {@code @Test}s against the in-memory store.
 */
public class CorrelationPropagationTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> domainStream;

	@BeforeEach
	void openDomainStream ( ) {
		domainStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"),
						MockDomainEvent.class);
	}

	@Test
	void aCommandsRaisedEventsAllCarryOneMintedCorrelationId ( ) {
		Mock domain = buildBoundedContext(baseBuilder());

		domain.execute(new RaiseTwoEventsCommand("a", "b"));

		List<? extends Event<MockDomainEvent>> events = domainEvents();
		assertEquals(2, events.size());
		String first = correlationIdOf(events.get(0));
		String second = correlationIdOf(events.get(1));
		assertNotNull(first, "a no-tracing execute still mints a correlation id at the edge");
		assertEquals(first, second, "both events of one command belong to one flow");
	}

	@Test
	void twoExecutionsAreTwoFlows ( ) {
		Mock domain = buildBoundedContext(baseBuilder());

		domain.execute(new RaiseTwoEventsCommand("a", "b"));
		domain.execute(new RaiseTwoEventsCommand("c", "d"));

		List<? extends Event<MockDomainEvent>> events = domainEvents();
		assertEquals(4, events.size());
		assertNotEquals(correlationIdOf(events.get(0)), correlationIdOf(events.get(2)),
				"each execution mints its own flow id");
	}

	@Test
	void anExplicitTracingsCorrelationIdIsReusedNotReplaced ( ) {
		Mock domain = buildBoundedContext(baseBuilder());

		domain.execute(new RaiseTwoEventsCommand("a", "b"),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-42"));

		assertEquals("flow-42", correlationIdOf(domainEvents().get(0)));
	}

	@Test
	void interactiveTranslationCarriesTheCallersCorrelationId ( ) {
		Mock domain = buildBoundedContext(baseBuilder().translator(new SomeInboundTranslator()));

		domain.translate(new SomeInboundEvent("hello"),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-t"));

		assertEquals("flow-t", correlationIdOf(domainEvents().get(0)),
				"the domain event a translator raises continues the caller's flow");
	}

	@Test
	void asyncTranslationCarriesTheInboundEventsCorrelationId ( ) {
		Mock domain = buildBoundedContext(baseBuilder().translator(new SomeInboundTranslator()));

		domain.incoming(new SomeInboundEvent("hello"),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-in"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, domainEvents().size()));
		assertEquals("flow-in", correlationIdOf(domainEvents().get(0)),
				"the translation of an inbound event continues the flow the inbound event carries");
	}

	@Test
	void aCorrelatedTodoItemStampsItsFlowOnWhatItsHandlingRaises ( ) {
		CorrelatedTodoList todoList = new CorrelatedTodoList();
		var builder = baseBuilder();
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new RecordingAutomation(todoList));
		Mock domain = buildBoundedContext(builder);

		domain.execute(new RaiseTwoEventsCommand("go", "on"),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-auto"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(
				() -> assertEquals(2, stored(SecondDomainEvent.class).size()));
		for ( Event<MockDomainEvent> raised : stored(SecondDomainEvent.class) ) {
			assertEquals("flow-auto", correlationIdOf(raised),
					"the event an automation raises for an item carries the flow of the event that caused the item");
		}
	}

	@Test
	void monitoringEventsCarryTheFlowsCorrelationId ( ) {
		List<EphemeralEvent<BoundedContextEvent>> observed = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildBoundedContext(baseBuilder().listener(observed::add));

		domain.execute(new RaiseTwoEventsCommand("a", "b"),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-mon"));

		EphemeralEvent<BoundedContextEvent> commandExecuted = observed.stream()
				.filter(e -> e.data() instanceof BoundedContextEvent.CommandExecuted)
				.findFirst().orElseThrow();
		assertEquals("flow-mon",
				commandExecuted.tags().tag(Tracing.TAG_CORRELATION_ID).orElseThrow().value(),
				"the observability record of a command is correlated with the domain events it reports on");
	}

	// ════════════════════════════════════════════════════════════════════
	// helpers
	// ════════════════════════════════════════════════════════════════════

	private String correlationIdOf ( Event<?> event ) {
		return event.tags().tag(Tracing.TAG_CORRELATION_ID).map(t -> t.value()).orElse(null);
	}

	private List<? extends Event<MockDomainEvent>> domainEvents ( ) {
		return domainStream.query(EventQuery.matchAll()).toList();
	}

	private List<? extends Event<MockDomainEvent>> stored ( Class<? extends MockDomainEvent> type ) {
		return domainStream.query(EventQuery.forEvents(EventTypesFilter.of(type), Tags.none())).toList();
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	static class RaiseTwoEventsCommand implements Command<MockDomainEvent> {

		private final String one;
		private final String two;

		RaiseTwoEventsCommand ( String one, String two ) {
			this.one = one;
			this.two = two;
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.noDecisionModels()
					.raiseEvent(new FirstDomainEvent(one), Tags.none())
					.raiseEvent(new FirstDomainEvent(two), Tags.none());
		}
	}

	static class SomeInboundTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			switch ( event ) {
				case SomeInboundEvent e -> context.event(new FirstDomainEvent(e.someValue()));
				default -> { }
			}
		}
	}

	/** The item carries the flow of the event that put it on the list. */
	record CorrelatedItem ( String value, String correlationId ) implements CorrelatedTodoItem { }

	static class CorrelatedTodoList implements TodoListReadModel<MockDomainEvent,CorrelatedItem> {

		private final List<CorrelatedItem> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		@Override
		public String readmodelName ( ) {
			return "todo-correlated";
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
				// the flow an item belongs to is visible exactly here, on the triggering event's tags
				case FirstDomainEvent f -> items.add(new CorrelatedItem(f.value(), Tracing.readFrom(event).correlationId()));
				case SecondDomainEvent s -> items.removeIf(item -> item.value().equals(s.value()));
				default -> { }
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<CorrelatedItem> streamItems ( Limit limit ) {
			return List.copyOf(items).stream().limit(limit.isSet() ? limit.value() : Long.MAX_VALUE);
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}

	/** Named rather than anonymous: an automation's bookmark is keyed on its simple name. */
	static class RecordingAutomation implements Automation<CorrelatedItem,MockDomainEvent,MockOutboundEvent> {

		private final CorrelatedTodoList todoList;

		RecordingAutomation ( CorrelatedTodoList todoList ) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,CorrelatedItem> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( CorrelatedItem todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return context.event(new SecondDomainEvent(todoItem.value()), "handled:" + todoItem.value());
		}
	}

}
