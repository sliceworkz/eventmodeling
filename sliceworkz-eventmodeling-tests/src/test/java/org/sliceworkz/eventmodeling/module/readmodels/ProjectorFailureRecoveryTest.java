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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.readmodels.SelfBookmarkingProjection;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * A projection failure that is not known to be permanent no longer retires the projector: the
 * processor backs off and retries, and because the eventstore's {@code Projector} rolled its cursor
 * back to the start of the failed batch, the retry re-offers exactly those events — no event is ever
 * skipped, and once the cause clears the read model catches up on its own. Before this, one throwable
 * out of a projection — a two-second blip toward the database a read model writes into — retired the
 * processor for the life of the process, and only a bounded context restart brought it back.
 * <p>
 * The permanent half — a poison event still stops the projector, and says so — is pinned by
 * {@code ReadModelProjectorLifecycleTest}; the leadership consequences by {@code LeaderElectionTest}.
 * <p>
 * The retry pacing is shortened through the {@code sliceworkz.eventmodeling.projector.retry.*}
 * system properties, which are read when the bounded context is built — the same seam an operator
 * has.
 */
public class ProjectorFailureRecoveryTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ProjectorRecoveryBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private String previousInitial;
	private String previousMax;

	@BeforeEach
	void shortenRetryPacing ( ) {
		previousInitial = System.setProperty("sliceworkz.eventmodeling.projector.retry.initial.ms", "50");
		previousMax = System.setProperty("sliceworkz.eventmodeling.projector.retry.max.ms", "200");
	}

	@AfterEach
	void restoreRetryPacing ( ) {
		restore("sliceworkz.eventmodeling.projector.retry.initial.ms", previousInitial);
		restore("sliceworkz.eventmodeling.projector.retry.max.ms", previousMax);
	}

	private static void restore ( String property, String previous ) {
		if ( previous == null ) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

	/**
	 * The motivating scenario: the projection fails for a while — what a dead target database looks
	 * like from the processor — and then works again. The read model must catch up without anybody
	 * restarting anything, the rounds in between must be reported as {@code ReadModelProjectorFailed},
	 * and the processor must never have been retired.
	 */
	@Test
	void aTransientProjectionFailureRecoversWithoutRestart ( ) {
		FlakyReadModel flaky = new FlakyReadModel("flaky-readmodel");
		flaky.failing.set(true);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(flaky).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("survives-the-outage"));

		// the failure is reported per fruitless round while the processor keeps retrying
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(failedCount("flaky-readmodel") >= 2,
						"every fruitless retry round must be reported: " + received));

		flaky.failing.set(false); // the outage ends; nothing is restarted

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("survives-the-outage"), flaky.applied(),
						"the read model must catch up on its own once the cause clears"));

		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.ReadModelProjectorStopped),
				"a failure worth retrying must not retire the processor: " + received);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.EventuallyConsistentReadModelUpdated u
								&& u.readModel().equals("flaky-readmodel")),
						"recovery shows as the next update, there is no 'recovered' event: " + received));
	}

	/**
	 * {@code consecutiveFailedRuns} is what a consumer keys alerting on, so it has to actually count:
	 * climbing once per fruitless round, and starting over at 1 for a failure after a recovery —
	 * a projector that got somewhere in between is not "still failing".
	 */
	@Test
	void theFailureCounterClimbsPerRoundAndResetsOnRecovery ( ) {
		FlakyReadModel flaky = new FlakyReadModel("counting-readmodel");
		flaky.failing.set(true);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(flaky).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("first-outage"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(failedCount("counting-readmodel") >= 3, "three rounds must have failed: " + received));

		List<Integer> counts = failedRunCounts("counting-readmodel");
		assertEquals(List.of(1, 2, 3), counts.subList(0, 3),
				"the counter must climb once per fruitless round: " + counts);

		flaky.failing.set(false);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("first-outage"), flaky.applied(), "the first outage must clear"));

		int failedBeforeSecondOutage = failedCount("counting-readmodel");
		flaky.failing.set(true);
		domain.event(new FirstDomainEvent("second-outage"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(failedCount("counting-readmodel") > failedBeforeSecondOutage,
						"the second outage must be reported too: " + received));
		assertEquals(1, failedRunCounts("counting-readmodel").get(failedBeforeSecondOutage).intValue(),
				"a failure after a recovery starts a new streak at 1: " + failedRunCounts("counting-readmodel"));

		flaky.failing.set(false); // let teardown find a healthy processor
	}

	/**
	 * The never-skip half, against a projection that commits per batch the way a durable read model
	 * does: a commit that fails takes the batch with it, and the retry lands every event exactly once.
	 * Fails as zero rows without the retry, and as doubled rows without the eventstore's cursor
	 * rollback — the {@code SqlReadModelSurvivesRestartTest} shape, at the processor level.
	 */
	@Test
	void aRetriedBatchSkipsNothingAndDoublesNothing ( ) {
		CommittingReadModel committing = new CommittingReadModel("committing-readmodel");
		committing.failNextCommit.set(true);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(committing).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("one"));
		domain.event(new FirstDomainEvent("two"));
		domain.event(new FirstDomainEvent("three"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(3, committing.committed().size(),
						"every event must land after the failed commit was retried: " + committing.committed()));

		assertEquals(3, committing.committed().stream().distinct().count(),
				"no event may be committed twice across the retry: " + committing.committed());
		assertTrue(failedCount("committing-readmodel") >= 1,
				"the failed commit must have been reported as a fruitless round: " + received);
		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.ReadModelProjectorStopped),
				"a failing commit is retried, not fatal: " + received);
	}

	/**
	 * The promotion path has to be paced too. A promotion rebuilds the projector, and for a read model
	 * that keeps its own bookmark that rebuild reads {@code resumeFrom()} — which hits the very
	 * database whose outage this test simulates, and throws <em>outside</em> the projector run. The
	 * loop's catch-all used to go straight round again: a reseed failing against a dead database was
	 * retried at full thread speed. Paced, a window this size sees a handful of attempts; unpaced it
	 * sees thousands.
	 */
	@Test
	void aFailingReseedDoesNotSpin ( ) throws InterruptedException {
		ReseedFailingReadModel reseedFailing = new ReseedFailingReadModel("reseed-failing-readmodel");

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(reseedFailing).eventuallyConsistent();
		buildBoundedContext(builder); // SHARED: promoted by the elector's synchronous first round

		Thread.sleep(1500);

		int attempts = reseedFailing.resumeFromCalls.get();
		assertTrue(attempts <= 4,
				"a reseed failing against a dead database must be paced like a poll, not spun: " + attempts + " attempts in 1.5s");
		assertTrue(attempts >= 2, "the reseed must actually have been attempted: " + attempts);
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private int failedCount ( String readModel ) {
		return failedRunCounts(readModel).size();
	}

	/** The {@code consecutiveFailedRuns} of every {@code ReadModelProjectorFailed} for this read model, in order. */
	private List<Integer> failedRunCounts ( String readModel ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.ReadModelProjectorFailed.class::isInstance)
					.map(BoundedContextEvent.ReadModelProjectorFailed.class::cast)
					.filter(e -> readModel.equals(e.readModel()))
					.map(BoundedContextEvent.ReadModelProjectorFailed::consecutiveFailedRuns)
					.toList();
		}
	}

	/** A read model whose projection fails while the flag is set — the shape of a target-database outage. */
	static class FlakyReadModel implements ReadModel<MockDomainEvent> {

		final AtomicBoolean failing = new AtomicBoolean();
		private final String name;
		private final List<String> applied = Collections.synchronizedList(new ArrayList<>());

		FlakyReadModel ( String name ) {
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
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void when ( MockDomainEvent event ) {
			if ( failing.get() ) {
				throw new IllegalStateException("the database this read model writes into is down");
			}
			applied.add(((FirstDomainEvent) event).value());
		}

		List<String> applied ( ) {
			return List.copyOf(applied);
		}
	}

	/**
	 * A projection that commits per batch, the way a durable read model does: {@code when} buffers,
	 * {@code afterBatch} commits — and can fail, which must take the whole batch with it so the retry
	 * re-offers every event of it.
	 */
	static class CommittingReadModel implements ReadModelWithMetaData<MockDomainEvent>, BatchAwareProjection<MockDomainEvent> {

		final AtomicBoolean failNextCommit = new AtomicBoolean();
		private final String name;
		private final List<String> pending = Collections.synchronizedList(new ArrayList<>());
		private final List<String> committed = Collections.synchronizedList(new ArrayList<>());

		CommittingReadModel ( String name ) {
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
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void beforeBatch ( ) {
			pending.clear(); // a batch whose commit failed is not cancelled, so clear its leftovers here
		}

		@Override
		public void when ( org.sliceworkz.eventstore.events.Event<MockDomainEvent> event ) {
			pending.add(((FirstDomainEvent) event.data()).value());
		}

		@Override
		public void afterBatch ( Optional<EventReference> lastEventReference ) {
			if ( failNextCommit.compareAndSet(true, false) ) {
				throw new IllegalStateException("commit failed");
			}
			committed.addAll(pending);
			pending.clear();
		}

		@Override
		public void cancelBatch ( ) {
			pending.clear();
		}

		List<String> committed ( ) {
			return List.copyOf(committed);
		}
	}

	/**
	 * A SHARED read model keeping its own bookmark whose {@code resumeFrom()} works once — at
	 * construction — and then fails, which is what a promotion's reseed looks like while the read
	 * model's database is down.
	 */
	static class ReseedFailingReadModel implements ReadModelWithMetaData<MockDomainEvent>, SelfBookmarkingProjection {

		final AtomicInteger resumeFromCalls = new AtomicInteger();
		private final String name;

		ReseedFailingReadModel ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void when ( org.sliceworkz.eventstore.events.Event<MockDomainEvent> event ) {
			// never reached: the reseed fails before any run
		}

		@Override
		public Optional<EventReference> resumeFrom ( ) {
			if ( resumeFromCalls.incrementAndGet() > 1 ) {
				throw new IllegalStateException("the database holding this read model's bookmark is down");
			}
			return Optional.empty();
		}
	}

}
