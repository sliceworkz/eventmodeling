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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Observability must never be able to fail the work it observes. A {@link BoundedContextListener} is
 * invoked synchronously on the thread doing the work, and at three of its call sites an escaping
 * exception is not merely noise:
 * <ul>
 *   <li>{@link CommandExecuted} is emitted after the command's events are durably appended, so a
 *       throw reports a command that succeeded as failed — and the caller's natural response to
 *       that is to run it again.</li>
 *   <li>In the automation processor the emission sits before the bookmark that records the batch's
 *       progress.</li>
 *   <li>A projector's run listener throws inside the projection loop rather than beside it.</li>
 * </ul>
 * The kernel therefore contains every delivery: the exception is caught, counted on
 * {@code sliceworkz.eventmodeling.listener.failure} and logged at ERROR, and the operation carries
 * on as if no listener were registered. Nothing replays the dropped event.
 *
 * @see BoundedContextEventEmitter
 */
public class BoundedContextListenerFailureTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ListenerFailureTestBoundedContext";

	private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private Mock buildDomain ( BoundedContextListener listener ) {
		return buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.meterRegistry(meterRegistry)
				.listener(listener));
	}

	/**
	 * A listener that records everything it is handed, and throws on the events matching a predicate.
	 * The failing event is recorded before the throw, so a test can tell "was not delivered" apart from
	 * "was delivered and rejected".
	 */
	private static class ThrowingListener implements BoundedContextListener {

		private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		private final Predicate<BoundedContextEvent> failOn;
		private final AtomicInteger failures = new AtomicInteger();

		ThrowingListener ( Predicate<BoundedContextEvent> failOn ) {
			this.failOn = failOn;
		}

		@Override
		public void on ( org.sliceworkz.eventstore.events.EphemeralEvent<BoundedContextEvent> event ) {
			received.add(event.data());
			if ( failOn.test(event.data()) ) {
				failures.incrementAndGet();
				throw new IllegalStateException("deliberate listener failure on " + event.data().getClass().getSimpleName());
			}
		}

		List<BoundedContextEvent> received ( ) {
			return List.copyOf(received);
		}

		int failures ( ) {
			return failures.get();
		}
	}

	/**
	 * The headline: the command's events are already durably appended by the time {@code CommandExecuted}
	 * is emitted, so a throwing listener must not turn a success into a failure the caller would retry.
	 */
	@Test
	void aFailingListenerDoesNotFailACommandThatSucceeded ( ) {
		ThrowingListener listener = new ThrowingListener(event -> event instanceof CommandExecuted);
		Mock domain = buildDomain(listener);

		Optional<EventReference> reference = domain.execute(new MockCommand(List.of(new FirstDomainEvent("x"))));

		assertTrue(reference.isPresent(), "the command succeeded, so it must report the event it appended");
		assertEquals(1, listener.failures(), "the listener really did throw");

		// and the event is genuinely in the store - the append happened before the listener ran
		try ( EventStore store = EventStoreFactory.get().eventStore(eventStorage()) ) {
			var stored = store
					.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("domain"), MockDomainEvent.class)
					.query(EventQuery.matchAll()).toList();
			assertEquals(1, stored.size(), "the domain event must be durably appended: " + stored);
			assertEquals(new FirstDomainEvent("x"), stored.get(0).data());
		}
	}

	/**
	 * The failure mode is worse than an escaping exception: the throw lands in {@code DCBModule}'s own
	 * {@code catch ( RuntimeException )}, which emits {@code CommandFailed} for the very command that
	 * just succeeded. An observer would then see a command reported both ways, or only as failed.
	 */
	@Test
	void aSuccessfulCommandIsNeverReportedAsFailedBecauseTheListenerThrew ( ) {
		ThrowingListener listener = new ThrowingListener(event -> event instanceof CommandExecuted);
		Mock domain = buildDomain(listener);

		domain.execute(new MockCommand(List.of(new FirstDomainEvent("x"))));

		assertFalse(listener.received().stream().anyMatch(e -> e instanceof CommandFailed),
				"a command that appended its events is not a failed command, whatever the listener did: " + listener.received());
	}

	/**
	 * Containment is per delivery, not per listener: one rejected event does not retire the listener, and
	 * the events after it arrive normally.
	 */
	@Test
	void aFailingDeliveryDoesNotStarveTheEventsAfterIt ( ) {
		AtomicInteger seen = new AtomicInteger();
		ThrowingListener listener = new ThrowingListener(event -> event instanceof CommandExecuted && seen.incrementAndGet() == 1);
		Mock domain = buildDomain(listener);

		domain.execute(new MockCommand(List.of(new FirstDomainEvent("first"))));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("second"))));

		assertEquals(1, listener.failures(), "only the first delivery should have thrown");
		assertEquals(2, listener.received().stream().filter(e -> e instanceof CommandExecuted).count(),
				"the second command's event must still be delivered: " + listener.received());
	}

	/**
	 * The meter is the part that is never throttled, so it - not the log - is what an alert is built on.
	 * It is tagged with the bounded context and the event type whose delivery failed.
	 */
	@Test
	void everyFailedDeliveryIsCounted ( ) {
		ThrowingListener listener = new ThrowingListener(event -> event instanceof CommandExecuted);
		Mock domain = buildDomain(listener);

		domain.execute(new MockCommand(List.of(new FirstDomainEvent("a"))));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("b"))));
		domain.execute(new MockCommand(List.of(new FirstDomainEvent("c"))));

		Counter counter = meterRegistry.find("sliceworkz.eventmodeling.listener.failure")
				.tag("context", CONTEXT_NAME)
				.tag("event", "CommandExecuted")
				.counter();

		assertNotNull(counter, "expected a failure counter tagged with the context and the event type");
		assertEquals(3.0, counter.count(), "every failure counts, however much the logging is throttled");
	}

	/**
	 * A listener that cannot work at all is reported and counted, but does not stop the context coming up.
	 * The alternative - failing the boot - turns a transient blip in whatever the listener writes to into
	 * an outage of the application it was only meant to observe.
	 */
	@Test
	void aListenerThatAlwaysThrowsDoesNotPreventTheContextFromStarting ( ) {
		ThrowingListener listener = new ThrowingListener(event -> true);

		Mock domain = buildDomain(listener);

		assertTrue(listener.received().stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStarted),
				"start must run to completion and emit Started: " + listener.received());
		assertTrue(listener.failures() >= 2, "every lifecycle delivery threw and was contained");

		// and the context is genuinely usable afterwards
		assertTrue(domain.execute(new MockCommand(List.of(new FirstDomainEvent("x")))).isPresent());
	}

	/**
	 * {@code EventuallyConsistentReadModelUpdated} is emitted from inside the projector's own loop, so a
	 * throw there lands on the projection rather than beside it. The read model must keep catching up
	 * regardless.
	 * <p>
	 * This one passes without the containment too: the processor loop has a catch-all of its own, so an
	 * escaping listener degrades the projection into an error-and-retry cycle instead of killing it. That
	 * is exactly why containment belongs at the emitter — the loop's catch-all cannot tell a broken
	 * listener apart from a broken projection, and answers both by abandoning the round it was in.
	 */
	@Test
	void aFailingListenerDoesNotDerailAProjector ( ) {
		ThrowingListener listener = new ThrowingListener(event -> event instanceof BoundedContextEvent.EventuallyConsistentReadModelUpdated);
		MockReadModel readModel = new MockReadModel("listener-failure-readmodel", List.of(FirstDomainEvent.class), ReadModelStorage.EPHEMERAL);

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.meterRegistry(meterRegistry)
				.listener(listener);
		builder.readmodel(readModel).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		for ( int i = 0; i < 5; i++ ) {
			domain.event(new FirstDomainEvent("event-" + i));
		}

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(5, readModel.eventCount(), "the projector must keep projecting past a listener that throws"));

		assertTrue(listener.failures() > 0, "the run listener really did throw");
	}

	/**
	 * In the automation processor the emission of {@code AutomationProcessed} sits before the bookmark
	 * that records the batch's progress, inside the loop's own catch-all. Contained, the automation
	 * drains its todo list and picks up work arriving afterwards.
	 * <p>
	 * Note what this does and does not prove. Like the projector scenario above it passes without the
	 * containment, because the loop's own catch-all keeps the thread alive — but that path skips the
	 * bookmark at the end of the batch, so the processor re-reads a todo list it has already worked.
	 * Whether that produces a duplicate is a race against the todo list's own projection, and a duplicate
	 * is legal anyway under at-least-once delivery, so there is nothing deterministic to assert on. What
	 * is pinned here is the guarantee that matters and holds either way: a listener failure is not an
	 * automation failure.
	 */
	@Test
	void aFailingListenerDoesNotDerailAnAutomation ( ) {
		ThrowingListener listener = new ThrowingListener(event -> event instanceof BoundedContextEvent.AutomationProcessed);
		TodoList todoList = new TodoList("listener-failure-todo");
		List<String> handled = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.meterRegistry(meterRegistry)
				.listener(listener);
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new HandlingAutomation(todoList, handled));
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("one"));
		domain.event(new FirstDomainEvent("two"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(handled.containsAll(List.of("one", "two")), "the automation must drain its todo list: " + handled));

		// still alive: work arriving after the failures is picked up
		domain.event(new FirstDomainEvent("three"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(handled.contains("three"), "the automation must still pick up new work: " + handled));

		assertTrue(listener.failures() > 0, "the automation's emission really did throw");
		assertTrue(domain.automations().get(0).running(), "a listener failure is not an automation failure");
	}

	// ---------------------------------------------------------------------------------------------

	static class TodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		TodoList ( String name ) {
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
	}

	static class HandlingAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final TodoList todoList;
		private final List<String> handled;

		HandlingAutomation ( TodoList todoList, List<String> handled ) {
			this.todoList = todoList;
			this.handled = handled;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String item, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			handled.add(item);
			return context.event(new SecondDomainEvent(item));
		}
	}

}
