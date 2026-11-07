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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.UnclaimedInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockPublisher;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A read model can be projected from the inbound and the outbound stream, not only from the domain stream:
 * registered on {@code builder.inbound()} / {@code builder.outbound()} and read through
 * {@code context.inbound()} / {@code context.outbound()}. What came in, counted per type, whether a translator
 * claimed it or not; what went out, counted per type.
 * <p>
 * Pinned here: both projections — eventually consistent and live — follow their own stream and nothing else;
 * a read model over the inbound stream and one over the domain stream may share a name, since each stream keys
 * its bookmarks under a processor type of its own; an operator addresses them by a kind of their own; their
 * lifecycle events name the stream; and {@code build()} holds each registration to its stream's event type.
 */
public class InboundAndOutboundReadModelTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "StreamsBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	@ForEachBackend
	void anEventuallyConsistentReadModelCountsWhatCameIn ( ) {
		InboundTraffic traffic = new InboundTraffic();
		BoundedContextBuilder<Mock> builder = builder();
		builder.inbound().readmodel(traffic).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.inbound().incoming(new SomeInboundEvent("a"));
		domain.inbound().incoming(new SomeInboundEvent("b"));
		domain.inbound().incoming(new UnclaimedInboundEvent("c"));
		// a domain event must not reach a read model over the inbound stream
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("domain"))));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(
				Map.of("SomeInboundEvent", 2, "UnclaimedInboundEvent", 1),
				domain.inbound().read(InboundTraffic.class).published().data()));
		assertSame(traffic, domain.inbound().read(InboundTraffic.class),
				"read without parameters, an eventually consistent read model is the registered instance");
	}

	@ForEachBackend
	void anEventuallyConsistentReadModelCountsWhatWentOut ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.outbound()
				.publisher(new MockPublisher())
				.readmodel(new OutboundTraffic()).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.execute(new MockCommand(List.of(new FirstDomainEvent("one"))));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("two"))));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(
				Map.of("SomeOutboundEvent", 2),
				domain.outbound().read(OutboundTraffic.class).published().data()));
	}

	@ForEachBackend
	void aLiveReadModelIsProjectedFromItsOwnStreamPerRead ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.inbound().readmodel(InboundValues.class).live();
		Mock domain = buildBoundedContext(builder);

		domain.inbound().incoming(new SomeInboundEvent("x"));
		domain.inbound().incoming(new SomeInboundEvent("y"));
		domain.inbound().incoming(new SomeInboundEvent("x"));

		assertEquals(2, domain.inbound().read(InboundValues.class, "x").count());
		assertEquals(1, domain.inbound().read(InboundValues.class, "y").count());
		assertEquals(0, domain.inbound().read(InboundValues.class, "z").count());
	}

	@ForEachBackend
	void aReadModelOverTheInboundStreamMayShareItsNameWithOneOverTheDomainStream ( ) {
		// the two would share one bookmark if they shared a processor type, and each would skip what the other read
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(new Traffic<MockDomainEvent>()).eventuallyConsistent();
		builder.inbound().readmodel(new Traffic<MockInboundEvent>()).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.inbound().incoming(new SomeInboundEvent("in"));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("one"))));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("two"))));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertEquals(Map.of("FirstDomainEvent", 2), domain.read(Traffic.class).published().data());
			assertEquals(Map.of("SomeInboundEvent", 1), domain.inbound().read(Traffic.class).published().data());
		});
	}

	@ForEachBackend
	void anOperatorAddressesTheProjectorByTheKindOfItsStream ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.inbound().readmodel(new InboundTraffic()).eventuallyConsistent();
		builder.outbound().readmodel(new OutboundTraffic()).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		assertEquals(List.of(ProcessorKind.INBOUND_READ_MODEL), kindsOf(domain, "InboundTraffic"));
		assertEquals(List.of(ProcessorKind.OUTBOUND_READ_MODEL), kindsOf(domain, "OutboundTraffic"));
		assertThrows(IllegalArgumentException.class, () -> domain.stopProcessor(ProcessorKind.READ_MODEL, "InboundTraffic"),
				"the domain read models know no InboundTraffic");

		assertTrue(domain.stopProcessor(ProcessorKind.INBOUND_READ_MODEL, "InboundTraffic"));
		domain.inbound().incoming(new SomeInboundEvent("while stopped"));
		assertTrue(domain.restartProcessor(ProcessorKind.INBOUND_READ_MODEL, "InboundTraffic"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(
				Map.of("SomeInboundEvent", 1), domain.inbound().read(InboundTraffic.class).published().data()));
	}

	@Test
	void theProjectorsLifecycleNamesTheStream ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.inbound().readmodel(new InboundTraffic()).eventuallyConsistent();
		builder.readmodel(new Traffic<MockDomainEvent>()).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);
		domain.inbound().incoming(new SomeInboundEvent("a"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertEquals(BoundedContextStreams.INBOUND, started("InboundTraffic").stream());
			assertEquals(null, started("Traffic").stream(), "a read model over the domain stream names no stream");
			assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.EventuallyConsistentReadModelUpdated u
					&& u.readModel().equals("InboundTraffic") && BoundedContextStreams.INBOUND.equals(u.stream())), received::toString);
		});
	}

	@Test
	void theBuildRefusesAReadModelOfAnotherStreamsEventType ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.inbound().readmodel(new Traffic<MockDomainEvent>() { @Override public String readmodelName ( ) { return "DomainTypedOnInbound"; } })
				.eventuallyConsistent();
		builder.outbound().readmodel(InboundValues.class).live();
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, builder::build);
		assertFalse(refused.getMessage().isBlank());
		assertTrue(refused.getMessage().contains("InboundValues") && refused.getMessage().contains("outbound")
				&& refused.getMessage().contains("DomainTypedOnInbound") && refused.getMessage().contains("inbound"), refused.getMessage());
	}

	@Test
	void aTodoListOverTheInboundStreamIsNoTodoList ( ) {
		// an automation waits on a todo list projected from the domain stream: one registered on inbound()
		// bookmarks under another processor type, so the automation would wait on a bookmark nobody writes
		BoundedContextBuilder<Mock> builder = builder();
		InboundTodoList todoList = new InboundTodoList();
		builder.inbound().readmodel(todoList).eventuallyConsistent();
		builder.automation(new InboundTodoAutomation(todoList));
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(refused.getMessage().contains("InboundTodoAutomation"), refused.getMessage());
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private static List<ProcessorKind> kindsOf ( Mock domain, String name ) {
		return domain.processors().stream().filter(s -> s.name().equals(name)).map(ProcessorStatus::kind).toList();
	}

	private BoundedContextEvent.ReadModelProjectorStarted started ( String readModel ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.ReadModelProjectorStarted.class::isInstance)
					.map(BoundedContextEvent.ReadModelProjectorStarted.class::cast)
					.filter(e -> readModel.equals(e.readModel()))
					.findFirst().orElseThrow();
		}
	}

	/** Counts the events it is handed per stored type: over whichever stream it is registered on. */
	public static class Traffic<E> extends PublishingReadModel<E, Map<String, Integer>> {

		@Override
		protected Map<String, Integer> initialState ( ) {
			return Map.of();
		}

		@Override
		protected Map<String, Integer> apply ( Map<String, Integer> state, Event<E> event ) {
			Map<String, Integer> next = new HashMap<>(state);
			next.merge(EventType.of(event.data().getClass()).name(), 1, Integer::sum);
			return Map.copyOf(next);
		}

		@Override
		public EventFilter eventFilter ( ) {
			return EventFilter.matchAll();
		}
	}

	public static class InboundTraffic extends Traffic<MockInboundEvent> { }

	public static class OutboundTraffic extends Traffic<MockOutboundEvent> { }

	/** A live read model over the inbound stream: how many {@code SomeInboundEvent}s carried one value. */
	public static class InboundValues implements ReadModel<MockInboundEvent> {

		private final String value;
		private int count;

		public InboundValues ( String value ) {
			this.value = value;
		}

		@Override
		public EventFilter eventFilter ( ) {
			return EventFilter.forTypes(SomeInboundEvent.class);
		}

		@Override
		public void when ( Event<MockInboundEvent> event ) {
			if ( event.data() instanceof SomeInboundEvent e && e.someValue().equals(value) ) {
				count++;
			}
		}

		public int count ( ) {
			return count;
		}
	}

	public static class InboundTodoList implements org.sliceworkz.eventmodeling.automation.TodoListReadModel<MockInboundEvent, String> {

		@Override
		public EventFilter eventFilter ( ) {
			return EventFilter.matchAll();
		}

		@Override
		public void when ( Event<MockInboundEvent> event ) { }

		@Override
		public java.util.stream.Stream<String> streamItems ( org.sliceworkz.eventstore.query.Limit limit ) {
			return java.util.stream.Stream.empty();
		}

		@Override
		public java.util.Optional<org.sliceworkz.eventstore.events.EventReference> lastEventReference ( ) {
			return java.util.Optional.empty();
		}
	}

	public static class InboundTodoAutomation implements org.sliceworkz.eventmodeling.automation.Automation<String, MockInboundEvent> {

		private final InboundTodoList todoList;

		InboundTodoAutomation ( InboundTodoList todoList ) {
			this.todoList = todoList;
		}

		@Override
		public org.sliceworkz.eventmodeling.automation.TodoListReadModel<MockInboundEvent, String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public java.util.Optional<org.sliceworkz.eventstore.events.EventReference> handle ( String item,
				org.sliceworkz.eventmodeling.automation.AutomationContext<MockInboundEvent> context ) {
			return java.util.Optional.empty();
		}
	}

}
