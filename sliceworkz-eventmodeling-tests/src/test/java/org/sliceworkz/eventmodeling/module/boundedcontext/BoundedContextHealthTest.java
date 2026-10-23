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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextHealth;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextState;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.events.Tags;

/**
 * What {@link org.sliceworkz.eventmodeling.boundedcontext.HealthCapability#health()} reports for a bounded
 * context: its lifecycle, its processors retired, retrying or stopped by an operator, how far behind they
 * are, its part in leader election and its monitoring listener — beside the storage's own report — and
 * that the answer comes back within its timeout whatever the storage does.
 * <p>
 * Plain {@code @Test}s: this is the framework judging its own state, and the storage's checks are pinned
 * per backend in the eventstore.
 */
public class BoundedContextHealthTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "HealthBoundedContext";

	@Test
	void aRunningContextInWorkingOrderIsUpOnEveryCheck ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(new MockReadModel("health-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.translator(new ProcessorAdminTest.NoopTranslator());
		builder.dispatcher(new ProcessorAdminTest.NoopDispatcher());
		Mock domain = buildBoundedContext(builder);

		BoundedContextHealth health = domain.health();

		assertEquals(CONTEXT_NAME, health.boundedContext());
		assertEquals(BoundedContextState.RUNNING, health.state());
		assertEquals(HealthStatus.UP, health.status(), health.toString());
		assertEquals(List.of(BoundedContextHealth.LIFECYCLE, BoundedContextHealth.IN_MEMORY_READ_MODELS,
				BoundedContextHealth.READ_MODELS, BoundedContextHealth.TRANSLATORS, BoundedContextHealth.DISPATCHERS,
				BoundedContextHealth.PROCESSOR_LAG, BoundedContextHealth.LEADERSHIP),
			health.checks().stream().map(HealthCheck::name).toList(),
			"only what applies here: no automation, publisher or policy is deployed, and no listener is set");
		assertEquals(HealthStatus.UP, health.storage().status(), "the storage's own report travels with it");
		assertEquals(eventStorage().name(), health.storage().storage());
		HealthCheck leadership = health.check(BoundedContextHealth.LEADERSHIP).orElseThrow();
		assertEquals(2, leadership.details().get("leases"), "the translator and the dispatcher are leader-only");
		assertEquals(2, leadership.details().get("held"), "and a lone instance leads both");
	}

	@Test
	void theStateFollowsTheLifecycleAndOnlyARunningContextIsUp ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(new MockReadModel("lifecycle-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = (Mock) builder.build();
		boundedContext = domain;

		assertEquals(BoundedContextState.BUILT, domain.state());
		assertEquals(HealthStatus.DOWN, lifecycle(domain).status());
		assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.READ_MODELS).orElseThrow().status(),
			"a processor parked because the context never started is not judged");

		domain.start();
		assertEquals(BoundedContextState.RUNNING, domain.state());
		assertEquals(HealthStatus.UP, lifecycle(domain).status());

		domain.stop();
		assertEquals(BoundedContextState.STOPPED, domain.state());
		assertEquals(HealthStatus.DOWN, lifecycle(domain).status());
		assertFalse(domain.health().check(BoundedContextHealth.PROCESSOR_LAG).isPresent(), "nothing is measured of parked processors");

		domain.start();
		assertEquals(BoundedContextState.RUNNING, domain.state(), "a stopped context starts again");

		domain.terminate();
		assertEquals(BoundedContextState.TERMINATED, domain.state());
		BoundedContextHealth terminated = assertDoesNotThrow(() -> domain.health(), "a terminated context still answers");
		assertEquals(HealthStatus.DOWN, terminated.status());
	}

	@Test
	void aContextIsStartingWhileItsInMemoryReadModelsAreProjected ( ) throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blocked = new CountDownLatch(1);
		BlockingReadModel slow = new BlockingReadModel("slow-readmodel", release, blocked);
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(slow).eventuallyConsistent();
		Mock domain = (Mock) builder.build();
		boundedContext = domain;
		domain.event(new FirstDomainEvent("before")); // history the initial projection has to work through

		Thread starting = Thread.ofVirtual().start(domain::start);
		try {
			assertTrue(blocked.await(10, TimeUnit.SECONDS), "fixture: the initial projection is under way");
			assertEquals(BoundedContextState.STARTING, domain.state(), "start() has not returned: not ready to serve");
			assertEquals(HealthStatus.DOWN, lifecycle(domain).status());
		} finally {
			release.countDown();
		}
		starting.join(10_000);
		assertEquals(BoundedContextState.RUNNING, domain.state());
		assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.IN_MEMORY_READ_MODELS).orElseThrow().status());
	}

	@Test
	void aProjectorRetiredOnAFailureIsDegradedAndOneStoppedByAnOperatorIsNot ( ) {
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(new ProcessorAdminTest.PoisonedReadModel("poisoned-readmodel")).eventuallyConsistent();
		builder.readmodel(new MockReadModel("paused-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.stopProcessor(ProcessorKind.READ_MODEL, "paused-readmodel");
		domain.event(new FirstDomainEvent("poison"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
			assertEquals(HealthStatus.DEGRADED, domain.health().check(BoundedContextHealth.READ_MODELS).orElseThrow().status()));
		HealthCheck readModels = domain.health().check(BoundedContextHealth.READ_MODELS).orElseThrow();
		assertTrue(readModels.message().contains("poisoned-readmodel"), readModels.message());
		assertFalse(readModels.message().contains("paused-readmodel"), "an operator's stop is not a problem: " + readModels.message());
		assertEquals(1, readModels.details().get("stoppedOnFailure"));
		assertEquals(1, readModels.details().get("stoppedByOperator"));
		assertEquals(HealthStatus.DEGRADED, domain.health().status());
	}

	@Test
	void aProjectorRetryingAfterFailuresIsDegradedUntilItRecovers ( ) {
		AtomicBoolean failing = new AtomicBoolean(true);
		BoundedContextBuilder<Mock> builder = builder();
		builder.readmodel(new FlakyReadModel("flaky-readmodel", failing)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("blip"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			HealthCheck readModels = domain.health().check(BoundedContextHealth.READ_MODELS).orElseThrow();
			assertEquals(HealthStatus.DEGRADED, readModels.status());
			assertTrue(readModels.message().contains("retrying") && readModels.message().contains("flaky-readmodel"), readModels.message());
		});

		failing.set(false);
		domain.restartProcessor(ProcessorKind.READ_MODEL, "flaky-readmodel"); // a running one: retries after its backoff anyway
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
			assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.READ_MODELS).orElseThrow().status()));
	}

	@Test
	void aProcessorLeavingAnEventUnreadBeyondTheToleranceIsBehind ( ) throws Exception {
		CountDownLatch release = new CountDownLatch(0);
		BlockingReadModel slow = new BlockingReadModel("behind-readmodel", release, new CountDownLatch(1));
		BoundedContextBuilder<Mock> builder = builder().processorLagTolerance(Duration.ofMillis(200));
		builder.readmodel(slow).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		HealthCheck caughtUp = domain.health().check(BoundedContextHealth.PROCESSOR_LAG).orElseThrow();
		assertEquals(HealthStatus.UP, caughtUp.status(), caughtUp.toString());

		CountDownLatch hold = new CountDownLatch(1);
		slow.holdOn(hold);
		domain.event(new FirstDomainEvent("unread"));
		try {
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				HealthCheck lag = domain.health().check(BoundedContextHealth.PROCESSOR_LAG).orElseThrow();
				assertEquals(HealthStatus.DEGRADED, lag.status(), lag.toString());
				assertTrue(lag.message().contains("behind-readmodel"), lag.message());
				assertEquals(1, lag.details().get("beyondTolerance"));
			});
		} finally {
			hold.countDown();
		}
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
			assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.PROCESSOR_LAG).orElseThrow().status(),
				"once it has read the event it is caught up again"));
	}

	@Test
	void aFailingMonitoringListenerIsDegradedWhileItFails ( ) {
		AtomicBoolean listenerFails = new AtomicBoolean(false);
		BoundedContextBuilder<Mock> builder = builder().listener(event -> {
			if ( listenerFails.get() ) {
				throw new IllegalStateException("the monitoring store is down");
			}
		});
		builder.readmodel(new MockReadModel("listened-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);
		assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.MONITORING_LISTENER).orElseThrow().status());

		listenerFails.set(true);
		domain.event(new FirstDomainEvent("reported"));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
			assertEquals(HealthStatus.DEGRADED, domain.health().check(BoundedContextHealth.MONITORING_LISTENER).orElseThrow().status()));

		listenerFails.set(false);
		domain.event(new FirstDomainEvent("reported again"));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
			assertEquals(HealthStatus.UP, domain.health().check(BoundedContextHealth.MONITORING_LISTENER).orElseThrow().status()));
	}

	@Test
	void aStorageThatStopsAnsweringCostsTheTimeoutAndIsMeasuredOnce ( ) {
		AtomicBoolean hang = new AtomicBoolean(false);
		CountDownLatch never = new CountDownLatch(1);
		AtomicInteger headsWhileHanging = new AtomicInteger();
		EventStorage hanging = hangingOnHead(eventStorage(), hang, never, headsWhileHanging);
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(hanging)
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(new MockReadModel("hung-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);
		domain.event(new FirstDomainEvent("something to measure"));

		hang.set(true);
		try {
			for ( int i = 0; i < 3; i++ ) {
				long started = System.nanoTime();
				HealthCheck lag = domain.health(Duration.ofMillis(300)).check(BoundedContextHealth.PROCESSOR_LAG).orElseThrow();
				long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
				assertEquals(HealthStatus.UNKNOWN, lag.status(), lag.toString());
				assertTrue(tookMillis < 2_000, "the caller waits its timeout and no more, but took " + tookMillis + "ms");
			}
			assertEquals(1, headsWhileHanging.get(), "a measurement still in flight is waited on again, not joined by another");
		} finally {
			hang.set(false);
			never.countDown();
		}
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private static HealthCheck lifecycle ( Mock domain ) {
		return domain.health().check(BoundedContextHealth.LIFECYCLE).orElseThrow();
	}

	/** the storage itself, except that its head blocks while {@code hang} is set — a database that stopped answering */
	private static EventStorage hangingOnHead ( EventStorage delegate, AtomicBoolean hang, CountDownLatch release, AtomicInteger heads ) {
		return (EventStorage) Proxy.newProxyInstance(EventStorage.class.getClassLoader(), new Class<?>[] { EventStorage.class },
			(proxy, method, args) -> {
				if ( method.getName().equals("head") && hang.get() ) {
					heads.incrementAndGet();
					release.await();
				}
				try {
					return method.invoke(delegate, args);
				} catch ( InvocationTargetException e ) {
					throw e.getCause();
				}
			});
	}

	/** An in-memory read model whose {@code when} can be held: to keep a context starting, or a processor behind. */
	static class BlockingReadModel implements ReadModel<MockDomainEvent> {

		private final String name;
		private volatile CountDownLatch hold;
		private final CountDownLatch entered;

		BlockingReadModel ( String name, CountDownLatch hold, CountDownLatch entered ) {
			this.name = name;
			this.hold = hold;
			this.entered = entered;
		}

		void holdOn ( CountDownLatch hold ) {
			this.hold = hold;
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
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			entered.countDown();
			try {
				hold.await();
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** An in-memory read model that fails transiently while {@code failing} is set. */
	static class FlakyReadModel implements ReadModel<MockDomainEvent> {

		private final String name;
		private final AtomicBoolean failing;

		FlakyReadModel ( String name, AtomicBoolean failing ) {
			this.name = name;
			this.failing = failing;
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
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( failing.get() ) {
				throw new IllegalStateException("the database this read model writes to is down");
			}
		}
	}

}
