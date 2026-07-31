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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.EventuallyConsistentReadModelUpdated;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.mock.sliced.SlicedCommand;
import org.sliceworkz.eventmodeling.mock.sliced.SlicedFeatureSlice;
import org.sliceworkz.eventmodeling.slices.Aspect;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Tests that a registered {@link BoundedContextListener} is notified of the
 * {@link BoundedContextEvent}s produced by the kernel of a bounded context.
 */
public class BoundedContextListenerTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ListenerTestBoundedContext";

	private Mock buildDomain(BoundedContextListener listener) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(listener);
		return buildBoundedContext(builder);
	}

	@Test
	void listenerReceivesStartingThenStartedOnStart() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(event -> received.add(event.data()));

		int starting = -1;
		int started = -1;
		for (int i = 0; i < received.size(); i++) {
			if (received.get(i) instanceof BoundedContextEvent.BoundedContextStarting) starting = i;
			if (received.get(i) instanceof BoundedContextStarted) started = i;
		}

		assertTrue(starting >= 0, "expected a BoundedContextStarting event, got: " + received);
		assertTrue(started >= 0, "expected a BoundedContextStarted event, got: " + received);
		assertTrue(starting < started, "Starting should be emitted before Started");
	}

	@Test
	void startingAnnouncesTheContextAndStartedItsStartupDuration() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(event -> received.add(event.data()));

		BoundedContextEvent.BoundedContextStarting starting = received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received));
		BoundedContextStarted started = received.stream()
				.filter(e -> e instanceof BoundedContextStarted)
				.map(e -> (BoundedContextStarted) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarted event, got: " + received));

		assertEquals(CONTEXT_NAME, starting.boundedContext());
		assertNotNull(starting.enabledFeatures(), "Starting should announce the deployed feature slices");
		assertNotNull(starting.disabledFeatures(), "Starting should announce the undeployed feature slices");

		assertEquals(CONTEXT_NAME, started.boundedContext());
		assertTrue(started.startupDurationMs() >= 0, "Started should carry the time spent starting up");
	}

	@Test
	void listenerReceivesStoppingThenStoppedOnTerminate() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(event -> received.add(event.data())).terminate();

		int stopping = -1;
		int stopped = -1;
		for (int i = 0; i < received.size(); i++) {
			if (received.get(i) instanceof BoundedContextEvent.BoundedContextStopping) stopping = i;
			if (received.get(i) instanceof BoundedContextEvent.BoundedContextStopped) stopped = i;
		}

		assertTrue(stopping >= 0, "expected a BoundedContextStopping event, got: " + received);
		assertTrue(stopped >= 0, "expected a BoundedContextStopped event, got: " + received);
		assertTrue(stopping < stopped, "Stopping should be emitted before Stopped");
	}

	@Test
	void listenerReceivesCommandExecuted() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		Mock domain = buildDomain(event -> received.add(event.data()));

		MockCommand command = new MockCommand(List.of(new FirstDomainEvent("x")));
		domain.execute(command);

		CommandExecuted commandExecuted = received.stream()
				.filter(e -> e instanceof CommandExecuted)
				.map(e -> (CommandExecuted) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandExecuted event, got: " + received));

		assertEquals(CONTEXT_NAME, commandExecuted.boundedContext());
		assertEquals(command.commandName(), commandExecuted.command());
		assertNotNull(commandExecuted.metrics());
		// no feature slices were scanned (no rootPackage), so there is nothing to attribute to
		assertNull(commandExecuted.slice());
	}

	@Test
	void commandExecutedIsAttributedToOwningSlice() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()))
				.features()
					.rootPackage(SlicedFeatureSlice.class.getPackage())
					.done();
		Mock domain = buildBoundedContext(builder);

		domain.execute(new SlicedCommand());

		CommandExecuted commandExecuted = received.stream()
				.filter(e -> e instanceof CommandExecuted)
				.map(e -> (CommandExecuted) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandExecuted event, got: " + received));

		assertNotNull(commandExecuted.slice(), "expected the command to be attributed to its feature slice");
		assertEquals("Sliced", commandExecuted.slice().name());
		assertEquals(Type.STATE_CHANGE, commandExecuted.slice().type());
	}

	@Test
	void startingDeclaresTheCommandsOfASliceBeforeAnyHasRun() {
		// A command is not wired into anything, so without declaring it a slice can only be seen to
		// contain it once it has been executed. Declared, it is in the inventory from the start - and
		// under exactly the name the CommandExecuted will later carry, which is what lets an observer
		// match the two instead of reporting the command twice.
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()))
				.features()
					.rootPackage(SlicedFeatureSlice.class.getPackage())
					.done();
		Mock domain = buildBoundedContext(builder);

		BoundedContextEvent.BoundedContextStarting starting = received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received));

		BoundedContextEvent.FeatureSlice sliced = starting.enabledFeatures().stream()
				.filter(slice -> slice.name().equals("Sliced"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected the Sliced feature slice, got: " + starting.enabledFeatures()));

		assertEquals(Set.of(new BoundedContextEvent.SliceMember("Sliced", BoundedContextEvent.MemberKind.COMMAND, Aspect.COMMAND)), sliced.members(),
				"the command is declared, and attributed to the aspect it was registered in");

		// the deployment announces which aspects it runs, which is the other half of knowing where a member runs
		assertEquals(Set.of(Aspect.COMMAND, Aspect.QUERY, Aspect.AUTOMATION, Aspect.PROJECTION), starting.aspects());

		// and the declared name is the one the command reports when it actually runs
		domain.execute(new SlicedCommand());
		CommandExecuted executed = received.stream()
				.filter(e -> e instanceof CommandExecuted)
				.map(e -> (CommandExecuted) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a CommandExecuted event, got: " + received));
		assertEquals(sliced.members().iterator().next().name(), executed.command());
	}

	@Test
	void anInstanceRunningOnlySomeAspectsAnnouncesThoseAndDeclaresOnlyTheirMembers() {
		// The point of the aspects: parts of one slice run on different instances. An instance that
		// serves queries but runs no commands must say so, and must not claim the command members it
		// never configured - otherwise a reader cannot tell what actually runs where.
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()))
				.features()
					.rootPackage(SlicedFeatureSlice.class.getPackage())
					.disableCommands()
					.done();
		buildBoundedContext(builder);

		BoundedContextEvent.BoundedContextStarting starting = received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received));

		assertEquals(Set.of(Aspect.QUERY, Aspect.AUTOMATION, Aspect.PROJECTION), starting.aspects(),
				"an instance must announce exactly the aspects it runs");

		BoundedContextEvent.FeatureSlice sliced = starting.enabledFeatures().stream()
				.filter(slice -> slice.name().equals("Sliced"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected the Sliced feature slice"));
		assertTrue(sliced.members().isEmpty(),
				"the slice is deployed here, but its command aspect is not, so it declares no command");
	}

	@Test
	void aCommandDeclaredOutsideASliceBelongsToNoneAndACommandThatIsNotOneIsRejected() {
		// Registering straight on the builder (not from within a slice's configuration) has nothing to
		// attribute the command to, so it is quietly ignored rather than landing on an arbitrary slice.
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.command(MockCommand.class);
		buildBoundedContext(builder);

		BoundedContextEvent.BoundedContextStarting starting = received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received));
		assertTrue(starting.enabledFeatures().stream().allMatch(slice -> slice.members().isEmpty()));

		assertThrows(IllegalArgumentException.class, () -> builder.command(MockReadModel.class),
				"a class that is not a command must be rejected where it is registered, not silently reported as one");
	}

	@Test
	void emittedEventsCarryTracingTags() {
		List<EphemeralEvent<BoundedContextEvent>> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(received::add);

		assertFalse(received.isEmpty(), "expected at least the BoundedContextStarting/Started events");
		// kernel tracing attaches instance tags (x-instance-*) to every emitted event
		assertFalse(received.get(0).tags().tags().isEmpty(), "expected tracing tags on the emitted event");
	}

	@Test
	void eventuallyConsistentReadModelUpdatedCarriesQueryMetrics() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.readmodel(new MockReadModel("ec-model")).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.execute(new MockCommand(List.of(new FirstDomainEvent("a"), new FirstDomainEvent("b"))));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			EventuallyConsistentReadModelUpdated ec = received.stream()
					.filter(e -> e instanceof EventuallyConsistentReadModelUpdated)
					.map(e -> (EventuallyConsistentReadModelUpdated) e)
					.findFirst()
					.orElse(null);
			assertNotNull(ec, "expected an EventuallyConsistentReadModelUpdated event");
			// the fix: metrics come from the projector run, so queries are filled (not hardcoded to 0)
			assertTrue(ec.metrics().queriesDone() > 0, "queriesDone should be filled from the projector run, got: " + ec.metrics());
			assertTrue(ec.metrics().eventsHandled() > 0, "expected handled events");
			assertTrue(ec.metrics().eventsStreamed() >= ec.metrics().eventsHandled(), "streamed should be >= handled");
		});
	}

	@Test
	void streamAppendingListenerPersistsLifecycleEventToStream() {
		EventStream<BoundedContextEvent> kernelStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel"), BoundedContextEvent.class);

		buildDomain(new StreamAppendingBoundedContextListener(kernelStream));

		List<BoundedContextEvent> persisted = kernelStream.query(EventQuery.matchAll())
				.map(org.sliceworkz.eventstore.events.Event::data)
				.toList();

		assertTrue(persisted.stream().anyMatch(e -> e instanceof BoundedContextStarted),
				"expected a persisted BoundedContextStarted event, got: " + persisted);
	}

	@Test
	void readsBackAKernelEventStoredByAnOlderVersionOfTheFramework() {
		// A persisted kernel stream outlives the version that wrote it, and is typically read by another
		// process (a monitoring dashboard) running a version of its own: a BoundedContextStarted stored
		// before the event was split into Starting/Started still carries the feature slice inventory.
		// Binding it to the current record must not fail - that is what @JsonIgnoreProperties(ignoreUnknown)
		// on BoundedContextEvent is for; without it the store's strict deserializer rejects the event and
		// every reader of the stream breaks on the history it already has.
		EventStreamId streamId = EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel");
		eventStorage().append(AppendCriteria.none(), Optional.of(streamId), List.of(new EventToStore(streamId,
				EventType.of(BoundedContextStarted.class),
				"""
				{"boundedContext":"orders","logical":"orders","physical":"orders-1","process":"p123",\
				"enabledFeatures":[{"name":"PlaceOrder","type":"STATE_CHANGE","context":"orders","chapter":"checkout","tags":[]}],\
				"disabledFeatures":[]}""",
				null, Tags.none(), null)));

		EventStream<BoundedContextEvent> kernelStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(streamId, BoundedContextEvent.class);
		List<BoundedContextEvent> persisted = kernelStream.query(EventQuery.matchAll())
				.map(org.sliceworkz.eventstore.events.Event::data)
				.toList();

		assertEquals(1, persisted.size());
		BoundedContextStarted started = (BoundedContextStarted) persisted.getFirst();
		assertEquals("orders", started.boundedContext());
		assertEquals("orders-1", started.physical());
		assertEquals(0, started.startupDurationMs()); // the property did not exist when the event was written
	}

	@Test
	void readsBackASliceInventoryStoredBeforeSlicesDeclaredTheirMembers() {
		// Same concern one level down: a BoundedContextStarting written before FeatureSlice carried its
		// members must still bind, with the absent property reading as "declares nothing" rather than
		// null - otherwise every reader has to null-check a collection that is never null going forward.
		EventStreamId streamId = EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel-legacy-slices");
		eventStorage().append(AppendCriteria.none(), Optional.of(streamId), List.of(new EventToStore(streamId,
				EventType.of(BoundedContextEvent.BoundedContextStarting.class),
				"""
				{"boundedContext":"orders","logical":"orders","physical":"orders-1","process":"p123",\
				"enabledFeatures":[{"name":"PlaceOrder","type":"STATE_CHANGE","context":"orders","chapter":"checkout","tags":[]}],\
				"disabledFeatures":[]}""",
				null, Tags.none(), null)));

		EventStream<BoundedContextEvent> kernelStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(streamId, BoundedContextEvent.class);
		BoundedContextEvent.BoundedContextStarting starting = (BoundedContextEvent.BoundedContextStarting)
				kernelStream.query(EventQuery.matchAll()).map(org.sliceworkz.eventstore.events.Event::data).toList().getFirst();

		BoundedContextEvent.FeatureSlice placeOrder = starting.enabledFeatures().iterator().next();
		assertEquals("PlaceOrder", placeOrder.name());
		assertNotNull(placeOrder.members(), "an absent members property must read as an empty set, not null");
		assertTrue(placeOrder.members().isEmpty());
	}

}
