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
package org.sliceworkz.eventmodeling.module.leadership;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.junit.jupiter.api.Test;

/**
 * Leader election across two instances of one bounded context on one storage: exactly one instance
 * runs each leader-only processor, work moves — without loss or duplication — when the leader goes
 * away, and a higher-priority instance wins leadership back gracefully.
 * <p>
 * Two instances live in one JVM here, which is exactly what production does not do — and what makes
 * these tests deterministic: both electors, both sets of processors, one storage. The lease
 * semantics themselves (expiry, fencing, concurrency) are pinned per backend by the eventstore's
 * {@code LeaseTest}; these scenarios pin what the framework builds on top.
 * <p>
 * The todo list here is event-sourced all the way: handling an item raises a {@code SecondDomainEvent}
 * with an idempotency key derived from the item, and the todo list projects both the item's arrival
 * and its completion. That is the framework's own at-least-once answer, and it is what these tests
 * lean on to assert "no duplicates": a second instance taking over sees completed items as completed,
 * and a re-handled item de-duplicates on its key.
 */
public class LeaderElectionTest extends AbstractMockDomainTest {

	private static final Duration HEARTBEAT = Duration.ofMillis(200);
	private static final Duration TTL = Duration.ofMillis(600);

	private final List<BoundedContext<?,?,?>> contexts = new ArrayList<>();

	@AfterEach
	void terminateContexts ( ) {
		contexts.forEach(BoundedContext::terminate);
		contexts.clear();
	}

	private BoundedContextBuilder<Mock> newInstanceBuilder ( String node, long priority ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("LeaderElectionContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("election", node))
				.leadershipPriority(priority)
				.leadershipIntervals(HEARTBEAT, TTL);
	}

	private Mock startInstance ( BoundedContextBuilder<Mock> builder ) {
		Mock context = builder.build();
		contexts.add(context);
		context.start();
		return context;
	}

	private Mock instanceWith ( String node, long priority, RecordingAutomation automation, SharedApplyLog applyLog ) {
		BoundedContextBuilder<Mock> builder = newInstanceBuilder(node, priority);
		if ( automation != null ) {
			builder.readmodel(automation.todoList()).eventuallyConsistent();
			builder.automation(automation);
		}
		if ( applyLog != null ) {
			builder.readmodel(applyLog).eventuallyConsistent();
		}
		return startInstance(builder);
	}

	private static void appendWork ( Mock context, String... items ) {
		for ( String item : items ) {
			context.event(new FirstDomainEvent(item));
		}
	}

	/**
	 * The core single-writer guarantee, per backend: with two instances up, every todo item is
	 * handled exactly once, and all of them by the same (first-started, so elected) instance while
	 * it is alive.
	 */
	@ForEachBackend(requires = Capability.LEASE)
	public void testOnlyTheElectedLeaderHandlesTodoItems ( ) {
		RecordingAutomation onA = new RecordingAutomation("node-a");
		RecordingAutomation onB = new RecordingAutomation("node-b");

		Mock nodeA = instanceWith("node-a", 0, onA, null);
		instanceWith("node-b", 0, onB, null);

		appendWork(nodeA, "one", "two", "three", "four", "five");

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(5, onA.handled().size() + onB.handled().size(), "all items must be handled"));

		assertEquals(List.of(), onB.handled(), "the standby instance must handle nothing");
		assertEquals(5, onA.handled().stream().distinct().count(), "no item may be handled twice");
	}

	/** When the leader terminates, the standby takes over and the remaining work is done exactly once. */
	@Test
	public void testStandbyTakesOverWhenTheLeaderTerminates ( ) {
		RecordingAutomation onA = new RecordingAutomation("node-a");
		RecordingAutomation onB = new RecordingAutomation("node-b");

		Mock nodeA = instanceWith("node-a", 0, onA, null);
		Mock nodeB = instanceWith("node-b", 0, onB, null);

		appendWork(nodeA, "before-1", "before-2");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(2, onA.handled().size(), "the leader must handle the first items"));

		nodeA.terminate();

		appendWork(nodeB, "after-1", "after-2", "after-3");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(onB.handled().containsAll(List.of("after-1", "after-2", "after-3")),
						"the promoted standby must handle the items appended after the failover"));

		// nothing lost, nothing duplicated: 5 distinct items across both instances, each exactly once
		List<String> all = new ArrayList<>(onA.handled());
		all.addAll(onB.handled());
		assertEquals(5, all.size(), "every item exactly once across the failover, but saw: " + all);
		assertEquals(5, all.stream().distinct().count(), "no item may be handled twice across the failover, but saw: " + all);
	}

	/** A returning higher-priority instance wins leadership back through a graceful step-down. */
	@Test
	public void testHigherPriorityInstanceRegainsLeadership ( ) {
		RecordingAutomation onLow = new RecordingAutomation("node-low");
		RecordingAutomation onHigh = new RecordingAutomation("node-high");

		Mock lowPriority = instanceWith("node-low", 0, onLow, null);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(leaderFlagOf(lowPriority), "the only instance must lead"));

		// the preferred instance comes (back) up: the current leader steps down at its next renewal
		Mock highPriority = instanceWith("node-high", 10, onHigh, null);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertTrue(leaderFlagOf(highPriority), "the higher-priority instance must regain leadership");
			assertTrue(!leaderFlagOf(lowPriority), "the lower-priority instance must have stepped down");
		});

		appendWork(highPriority, "after-failback");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("after-failback"), onHigh.handled(), "work after the failback runs on the preferred instance"));
		assertEquals(List.of(), onLow.handled());
	}

	private static boolean leaderFlagOf ( Mock context ) {
		List<AutomationStatus> automations = context.automations();
		assertEquals(1, automations.size());
		return automations.get(0).leader();
	}

	/**
	 * A leader whose automation stops itself through {@code STOP_AUTOMATION} must hand its lease
	 * back, so a healthy instance takes the work over. The elector used to renew every lease
	 * unconditionally, consulting nothing about the processor's own state — so the stopped
	 * automation held its lease for the life of the process and its items sat outstanding on every
	 * instance of the deployment. Fails by timeout without the release.
	 */
	@Test
	public void testASelfStoppedAutomationHandsItsLeaseToAHealthyInstance ( ) {
		StoppableAutomation onA = new StoppableAutomation("node-a", true);
		StoppableAutomation onB = new StoppableAutomation("node-b", false);
		List<Object> kernelEventsOnA = new CopyOnWriteArrayList<>();

		BoundedContextBuilder<Mock> builderA = newInstanceBuilder("node-a", 0)
				.listener(event -> kernelEventsOnA.add(event.data()));
		builderA.readmodel(onA.todoList()).eventuallyConsistent();
		builderA.automation(onA);
		Mock nodeA = startInstance(builderA);
		// started alone, so its synchronous start round made it leader before B even exists
		assertTrue(leaderFlagOf(nodeA), "the first-started instance must lead");

		BoundedContextBuilder<Mock> builderB = newInstanceBuilder("node-b", 0);
		builderB.readmodel(onB.todoList()).eventuallyConsistent();
		builderB.automation(onB);
		startInstance(builderB);

		appendWork(nodeA, "poison", "behind-it");

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(onB.handled().containsAll(List.of("poison", "behind-it")),
						"the healthy instance must take the work over, but handled only: " + onB.handled()));
		assertEquals(List.of(), onA.handled(), "the failing instance must have handled nothing");
		assertTrue(kernelEventsOnA.stream().anyMatch(e -> e instanceof BoundedContextEvent.LeadershipReleased released
						&& released.reason() == BoundedContextEvent.LeadershipReleaseReason.PROCESSOR_STOPPED),
				"the failed instance must say why it gave the lease up, but emitted: " + kernelEventsOnA);
	}

	/**
	 * The same hand-over for a projector — and so for the translators and dispatchers that run on
	 * the same processor: a SHARED read model whose projection fails stops its projector (the
	 * {@code ProjectorException} path), and the lease must follow, so the healthy instance projects
	 * what the failed leader could not. Fails by timeout without the release.
	 */
	@Test
	public void testASelfStoppedProjectorHandsItsLeaseToAHealthyInstance ( ) {
		FlakyApplyLog onA = new FlakyApplyLog(true);
		FlakyApplyLog onB = new FlakyApplyLog(false);

		BoundedContextBuilder<Mock> builderA = newInstanceBuilder("node-a", 0);
		builderA.readmodel(onA).eventuallyConsistent();
		Mock nodeA = startInstance(builderA); // leads everything from its synchronous start round

		BoundedContextBuilder<Mock> builderB = newInstanceBuilder("node-b", 0);
		builderB.readmodel(onB).eventuallyConsistent();
		startInstance(builderB);

		appendWork(nodeA, "survives-the-failover");

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(Map.of("survives-the-failover", 1), FlakyApplyLog.applications(),
						"the healthy instance must project what the failed leader could not"));
	}

	/**
	 * A projector promoted after a spell as standby must resume from the durable shared position, not
	 * from its own stale in-memory cursor. The sequence forces exactly that: B leads and projects,
	 * A takes over (B's cursor now goes stale as A projects on), then B is promoted again — and must
	 * apply only what nobody has applied yet. Without the promotion re-seed, B re-applies everything
	 * A projected while B stood by.
	 */
	@Test
	public void testPromotedProjectorResumesFromTheSharedPositionNotItsStaleCursor ( ) {
		SharedApplyLog onA = new SharedApplyLog();
		SharedApplyLog onB = new SharedApplyLog();

		Mock nodeB = instanceWith("node-b", 0, null, onB);
		appendWork(nodeB, "phase1-a", "phase1-b", "phase1-c");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(3, SharedApplyLog.totalApplied(), "B must project phase 1"));

		// A takes over on priority; B's projector parks with its in-memory cursor at phase 1
		Mock nodeA = instanceWith("node-a", 10, null, onA);
		appendWork(nodeA, "phase2-a", "phase2-b", "phase2-c");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(6, SharedApplyLog.totalApplied(), "A must project phase 2"));

		// A goes away; B is promoted again and must NOT re-apply phase 2
		nodeA.terminate();
		appendWork(nodeB, "phase3-a", "phase3-b", "phase3-c");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(9, SharedApplyLog.totalApplied(), "B must project phase 3 after its re-promotion"));

		Map<String,Integer> applications = SharedApplyLog.applications();
		assertEquals(9, applications.size(), "nine distinct events applied, but saw: " + applications);
		applications.forEach((value, count) ->
				assertEquals(1, count, "event '%s' must be applied exactly once, but saw %s".formatted(value, applications)));
	}

	/**
	 * A storage without lease support gets the behaviour the framework had before leader election:
	 * everything runs on this instance (with a WARN that a second instance would duplicate work).
	 * A third-party {@code EventStorage} predating leases must keep working unchanged.
	 */
	@Test
	public void testStorageWithoutLeaseSupportFallsBackToRunningEverythingHere ( ) {
		RecordingAutomation automation = new RecordingAutomation("node-a");

		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name("LeaderElectionContext")
				.eventStorage(new LeaselessStorage(eventStorage()))
				.instance(InstanceFactory.determine("election", "node-a"))
				.leadershipIntervals(HEARTBEAT, TTL);
		builder.readmodel(automation.todoList()).eventuallyConsistent();
		builder.automation(automation);
		Mock context = builder.build();
		contexts.add(context);
		context.start();

		appendWork(context, "legacy-storage-item");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("legacy-storage-item"), automation.handled(),
						"on a lease-less storage the automation must run right here, as it always did"));
		assertTrue(leaderFlagOf(context), "the fallback must report the automation as leader");
	}

	/**
	 * A delegating storage that does not override the lease operations, so they hit the SPI defaults
	 * and throw {@code UnsupportedOperationException} — exactly what an {@code EventStorage}
	 * implementation written before leases existed looks like.
	 */
	static class LeaselessStorage implements org.sliceworkz.eventstore.spi.EventStorage {

		private final org.sliceworkz.eventstore.spi.EventStorage delegate;

		LeaselessStorage ( org.sliceworkz.eventstore.spi.EventStorage delegate ) {
			this.delegate = delegate;
		}

		@Override
		public String name ( ) {
			return delegate.name();
		}

		@Override
		public java.util.stream.Stream<StoredEvent> query ( EventQuery query, Optional<org.sliceworkz.eventstore.stream.EventStreamId> stream,
				EventReference after, Limit limit, QueryDirection queryDirection ) {
			return delegate.query(query, stream, after, limit, queryDirection);
		}

		@Override
		public List<StoredEvent> append ( org.sliceworkz.eventstore.stream.AppendCriteria appendCriteria,
				Optional<org.sliceworkz.eventstore.stream.EventStreamId> stream, List<EventToStore> events ) {
			return delegate.append(appendCriteria, stream, events);
		}

		@Override
		public Optional<StoredEvent> getEventById ( org.sliceworkz.eventstore.events.EventId eventId ) {
			return delegate.getEventById(eventId);
		}

		@Override
		public void subscribe ( EventStoreListener listener ) {
			delegate.subscribe(listener);
		}

		@Override
		public void unsubscribe ( EventStoreListener listener ) {
			delegate.unsubscribe(listener);
		}

		@Override
		public Optional<EventReference> getBookmark ( String reader ) {
			return delegate.getBookmark(reader);
		}

		@Override
		public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
			delegate.bookmark(reader, eventReference, tags);
		}

		@Override
		public void removeBookmark ( String reader ) {
			delegate.removeBookmark(reader);
		}

		@Override
		public List<org.sliceworkz.eventstore.events.Bookmark> getBookmarks ( ) {
			return delegate.getBookmarks();
		}

		@Override
		public void close ( ) {
			delegate.close();
		}
	}

	/**
	 * An automation that handles items and completes them through events, recording what it handled.
	 * The completion event carries an idempotency key derived from the item — the framework's own
	 * recipe for at-least-once delivery across restarts and failovers.
	 */
	static class RecordingAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final EventSourcedTodoList todoList;
		private final List<String> handled = new CopyOnWriteArrayList<>();

		RecordingAutomation ( String node ) {
			this.todoList = new EventSourcedTodoList("todo-of-" + node);
		}

		EventSourcedTodoList todoList ( ) {
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

	/**
	 * An automation that either handles items like {@link RecordingAutomation} or fails every one
	 * and asks to be stopped — the same class on both instances, so both contend for one lease,
	 * with only one of them broken.
	 */
	static class StoppableAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final EventSourcedTodoList todoList;
		private final boolean failing;
		private final List<String> handled = new CopyOnWriteArrayList<>();

		StoppableAutomation ( String node, boolean failing ) {
			this.todoList = new EventSourcedTodoList("stoppable-todo-of-" + node);
			this.failing = failing;
		}

		EventSourcedTodoList todoList ( ) {
			return todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			if ( failing ) {
				throw new IllegalStateException("this instance cannot handle '%s'".formatted(todoItem));
			}
			handled.add(todoItem);
			return context.event(new SecondDomainEvent(todoItem), "done-" + todoItem);
		}

		@Override
		public AutomationFailureAction onFailure ( String todoItem, Throwable cause, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return AutomationFailureAction.STOP_AUTOMATION;
		}

		List<String> handled ( ) {
			return List.copyOf(handled);
		}
	}

	/**
	 * A SHARED read model with one broken instance: {@code when} throws on the failing instance,
	 * which stops that instance's projector, while the healthy instance carries the same class name
	 * and so contends for the same lease. Applications are counted statically, across instances,
	 * like {@link SharedApplyLog}.
	 */
	static class FlakyApplyLog implements ReadModelWithMetaData<MockDomainEvent> {

		private static final Map<String,AtomicInteger> APPLICATIONS = new ConcurrentHashMap<>();

		private final boolean failing;

		FlakyApplyLog ( boolean failing ) {
			this.failing = failing;
		}

		static Map<String,Integer> applications ( ) {
			Map<String,Integer> snapshot = new ConcurrentHashMap<>();
			APPLICATIONS.forEach((value, count) -> snapshot.put(value, count.get()));
			return snapshot;
		}

		static void reset ( ) {
			APPLICATIONS.clear();
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( failing ) {
				throw new IllegalStateException("this instance cannot project '%s'".formatted(event.data()));
			}
			APPLICATIONS.computeIfAbsent(((FirstDomainEvent) event.data()).value(), v -> new AtomicInteger()).incrementAndGet();
		}
	}

	/**
	 * A todo list that is event-sourced all the way: items arrive as {@code FirstDomainEvent} and
	 * leave as {@code SecondDomainEvent}, so a fresh instance projecting from scratch sees completed
	 * items as completed — which is what makes a failover not re-handle them.
	 */
	static class EventSourcedTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> outstanding = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		EventSourcedTodoList ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			switch ( event.data() ) {
				case FirstDomainEvent added -> outstanding.add(added.value());
				case SecondDomainEvent done -> outstanding.remove(done.value());
				default -> { }
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return List.copyOf(outstanding).stream();
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}

	/**
	 * A SHARED read model recording, per event value, how often it was applied — across every
	 * instance, which is the point: SHARED means one storage all instances write, so a double
	 * application anywhere shows up here.
	 */
	static class SharedApplyLog implements ReadModelWithMetaData<MockDomainEvent> {

		private static final Map<String,AtomicInteger> APPLICATIONS = new ConcurrentHashMap<>();

		static Map<String,Integer> applications ( ) {
			Map<String,Integer> snapshot = new ConcurrentHashMap<>();
			APPLICATIONS.forEach((value, count) -> snapshot.put(value, count.get()));
			return snapshot;
		}

		static int totalApplied ( ) {
			return APPLICATIONS.values().stream().mapToInt(AtomicInteger::get).sum();
		}

		static void reset ( ) {
			APPLICATIONS.clear();
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			APPLICATIONS.computeIfAbsent(((FirstDomainEvent) event.data()).value(), v -> new AtomicInteger()).incrementAndGet();
		}
	}

	@Override
	@org.junit.jupiter.api.BeforeEach
	public void setUp ( ) {
		super.setUp();
		SharedApplyLog.reset();
		FlakyApplyLog.reset();
	}

}
