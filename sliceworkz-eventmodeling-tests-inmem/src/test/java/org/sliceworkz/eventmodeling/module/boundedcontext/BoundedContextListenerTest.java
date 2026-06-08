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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.mock.sliced.SlicedCommand;
import org.sliceworkz.eventmodeling.mock.sliced.SlicedFeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Tests that a registered {@link BoundedContextListener} is notified of the
 * {@link BoundedContextEvent}s produced by the kernel of a bounded context.
 */
public class BoundedContextListenerTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ListenerTestBoundedContext";

	private EventStorage eventStorage;

	@BeforeEach
	protected void setUp() {
		super.setUp();
		this.eventStorage = InMemoryEventStorage.newBuilder().build();
	}

	@AfterEach
	protected void tearDown() {
		if (boundedContext() != null) {
			boundedContext().terminate();
		}
	}

	private Mock buildDomain(BoundedContextListener listener) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"))
				.listener(listener);
		return buildBoundedContext(builder);
	}

	@Test
	void listenerReceivesBoundedContextStartedOnBuild() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(event -> received.add(event.data()));

		BoundedContextStarted started = received.stream()
				.filter(e -> e instanceof BoundedContextStarted)
				.map(e -> (BoundedContextStarted) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarted event, got: " + received));

		assertEquals(CONTEXT_NAME, started.boundedContext());
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
				.eventStorage(eventStorage)
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
	void emittedEventsCarryTracingTags() {
		List<EphemeralEvent<BoundedContextEvent>> received = Collections.synchronizedList(new ArrayList<>());

		buildDomain(received::add);

		assertFalse(received.isEmpty(), "expected at least the BoundedContextStarted event");
		// kernel tracing attaches instance tags (x-instance-*) to every emitted event
		assertFalse(received.get(0).tags().tags().isEmpty(), "expected tracing tags on the emitted event");
	}

	@Test
	void eventuallyConsistentReadModelUpdatedCarriesQueryMetrics() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.readmodel(new MockReadModel("ec-model")).shared().eventuallyConsistent();
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
		EventStream<BoundedContextEvent> kernelStream = EventStoreFactory.get().eventStore(eventStorage)
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel"), BoundedContextEvent.class);

		buildDomain(new StreamAppendingBoundedContextListener(kernelStream));

		List<BoundedContextEvent> persisted = kernelStream.query(EventQuery.matchAll())
				.map(org.sliceworkz.eventstore.events.Event::data)
				.toList();

		assertTrue(persisted.stream().anyMatch(e -> e instanceof BoundedContextStarted),
				"expected a persisted BoundedContextStarted event, got: " + persisted);
	}

}
