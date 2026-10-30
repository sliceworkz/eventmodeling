/*
 * Sliceworkz Eventmodeling - a lightweight Event Modeling implementation in Java
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextHealth;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextState;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextHealthTest.BlockingReadModel;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;

/**
 * {@code startInBackground()} returns into STARTING, while the in-memory read models are projected, and
 * the context reaches RUNNING by itself -- what lets a process answer its probes during a long start.
 */
public class BackgroundStartTest extends AbstractMockDomainTest {

	private final List<BoundedContextEvent> events = new CopyOnWriteArrayList<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private final CountDownLatch projecting = new CountDownLatch(1);

	@Test
	void aBackgroundStartReturnsWhileTheInMemoryReadModelsAreProjectedAndRunsOnceTheyAre ( ) throws Exception {
		Mock domain = buildWithHistory();

		CompletableFuture<Void> started = domain.startInBackground();

		assertTrue(projecting.await(10, TimeUnit.SECONDS), "fixture: the initial projection is under way");
		assertEquals(BoundedContextState.STARTING, domain.state(), "returned before the in-memory read models are projected");
		assertFalse(started.isDone());
		assertEquals(HealthStatus.DOWN, check(domain, BoundedContextHealth.LIFECYCLE).status(), "not ready while starting");
		HealthCheck inMemory = check(domain, BoundedContextHealth.IN_MEMORY_READ_MODELS);
		assertEquals("projecting: slow-readmodel", inMemory.message(), "the probe says what the start waits for");
		assertTrue(events.stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStarting));
		assertFalse(events.stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStarted), "announced once running, not before");

		release.countDown();
		started.get(10, TimeUnit.SECONDS);

		assertEquals(BoundedContextState.RUNNING, domain.state());
		assertEquals(HealthStatus.UP, check(domain, BoundedContextHealth.LIFECYCLE).status());
		assertEquals(1, events.stream().filter(e -> e instanceof BoundedContextEvent.BoundedContextStarted).count());
	}

	@Test
	void aStartWhileABackgroundStartIsUnderWayWaitsForIt ( ) throws Exception {
		Mock domain = buildWithHistory();
		CompletableFuture<Void> started = domain.startInBackground();
		assertTrue(projecting.await(10, TimeUnit.SECONDS));

		assertSame(started, domain.startInBackground(), "a second background start is the same start");
		Thread foreground = Thread.ofVirtual().start(domain::start);
		assertFalse(foreground.join(java.time.Duration.ofMillis(300)), "start() waits for the start under way");

		release.countDown();
		assertTrue(foreground.join(java.time.Duration.ofSeconds(10)));
		assertEquals(BoundedContextState.RUNNING, domain.state());
		assertEquals(1, events.stream().filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting).count(), "begun once");
		assertEquals(1, events.stream().filter(e -> e instanceof BoundedContextEvent.BoundedContextStarted).count());
	}

	@Test
	void aStopDuringABackgroundStartEndsItAndTheContextStartsAgain ( ) throws Exception {
		Mock domain = buildWithHistory();
		CompletableFuture<Void> started = domain.startInBackground();
		assertTrue(projecting.await(10, TimeUnit.SECONDS));

		domain.stop();
		started.get(10, TimeUnit.SECONDS);
		assertEquals(BoundedContextState.STOPPED, domain.state(), "a stop has the last word over a start in progress");
		assertFalse(events.stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStarted), "a start cut short is not announced as started");

		release.countDown();
		domain.start();
		assertEquals(BoundedContextState.RUNNING, domain.state());
	}

	@Test
	void aTerminateDuringABackgroundStartEndsIt ( ) throws Exception {
		Mock domain = buildWithHistory();
		CompletableFuture<Void> started = domain.startInBackground();
		assertTrue(projecting.await(10, TimeUnit.SECONDS));

		release.countDown(); // a projection holding its thread would hold up the drain, not the start
		domain.terminate();
		assertDoesNotThrow(() -> started.get(10, TimeUnit.SECONDS));
		assertEquals(BoundedContextState.TERMINATED, domain.state());
		assertThrows(IllegalStateException.class, domain::startInBackground);
	}

	@Test
	void aBackgroundStartWithNothingToProjectRunsAtOnce ( ) throws Exception {
		Mock domain = (Mock) builder().build();
		boundedContext = domain;

		domain.startInBackground().get(10, TimeUnit.SECONDS);

		assertEquals(BoundedContextState.RUNNING, domain.state());
		assertTrue(domain.startInBackground().isDone(), "already running: nothing to wait for");
	}

	private Mock buildWithHistory ( ) {
		BoundedContextBuilder<Mock> builder = builder().listener(event -> events.add(event.data()));
		builder.readmodel(new BlockingReadModel("slow-readmodel", release, projecting)).eventuallyConsistent();
		Mock domain = (Mock) builder.build();
		boundedContext = domain;
		domain.event(new FirstDomainEvent("before")); // history the initial projection has to work through
		return domain;
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("BackgroundStartContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private static HealthCheck check ( Mock domain, String name ) {
		return domain.health().check(name).orElseThrow();
	}
}
