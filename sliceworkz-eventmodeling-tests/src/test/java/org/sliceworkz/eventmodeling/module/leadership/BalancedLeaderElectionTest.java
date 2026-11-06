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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.LeadershipReleaseReason;
import org.sliceworkz.eventmodeling.boundedcontext.LeadershipDistribution;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Lease;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Who leads what under {@link LeadershipDistribution#BALANCED}: every lease goes to the instance that
 * weighs most for it, nothing moves while the instances stay the same, an instance restarting under
 * its name wins back what it had, instances starting together move no work, and a yielded lease is
 * not taken back. The mechanics both distributions share (failover, stalls, self-stopped processors)
 * are {@link LeaderElectionTest}'s.
 * <p>
 * The leader-only processors are SHARED read models' projectors, one lease each, twelve of them, so
 * the split between two instances is a real split and is predicted exactly from
 * {@link LeaderElector#weight}.
 */
public class BalancedLeaderElectionTest extends AbstractMockDomainTest {

	private static final Duration HEARTBEAT = Duration.ofMillis(200);
	private static final Duration TTL = Duration.ofMillis(600);
	private static final List<String> READ_MODELS = IntStream.rangeClosed(1, 12).mapToObj(i -> "shared-" + i).toList();

	private final List<BoundedContext<?,?,?>> contexts = new ArrayList<>();
	private final Map<String,List<Object>> kernelEvents = new ConcurrentHashMap<>();

	@AfterEach
	void terminateContexts ( ) {
		contexts.forEach(BoundedContext::terminate);
		contexts.clear();
	}

	private BoundedContextBuilder<Mock> newInstanceBuilder ( String node, long priority, Duration holdBack ) {
		List<Object> events = kernelEvents.computeIfAbsent(node, n -> new CopyOnWriteArrayList<>());
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name("BalancedElectionContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("election", node))
				.leadershipPriority(priority)
				.leadershipIntervals(HEARTBEAT, TTL)
				.leadershipStartupHoldBack(holdBack)
				.listener(event -> events.add(event.data()));
		READ_MODELS.forEach(name -> builder.readmodel(new NamedSharedReadModel(name)).eventuallyConsistent());
		return builder;
	}

	private Mock startInstance ( String node, long priority, Duration holdBack ) {
		Mock context = newInstanceBuilder(node, priority, holdBack).build();
		contexts.add(context);
		context.start();
		return context;
	}

	/** the read models this context leads, by name */
	private static Set<String> ledBy ( Mock context ) {
		return context.processors().stream()
				.filter(ProcessorStatus::leader)
				.map(ProcessorStatus::name)
				.filter(READ_MODELS::contains)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/** what rendezvous hashing assigns to the first node, of two, predicted from the lease names the storage holds */
	private Set<String> predictedFor ( String node, String other ) {
		Map<String,String> leaseNameByReadModel = new HashMap<>();
		for ( Lease lease : eventStorage().getLeases() ) {
			READ_MODELS.stream()
					.filter(name -> lease.leaseName().contains("/" + name + "["))
					.forEach(name -> leaseNameByReadModel.put(name, lease.leaseName()));
		}
		assertEquals(READ_MODELS.size(), leaseNameByReadModel.size(), "a lease per read model: " + leaseNameByReadModel);
		return leaseNameByReadModel.entrySet().stream()
				.filter(e -> LeaderElector.weight("election/" + node, e.getValue()) > LeaderElector.weight("election/" + other, e.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private void awaitTheHashedAssignment ( Mock nodeA, Mock nodeB ) {
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			Set<String> onA = ledBy(nodeA);
			Set<String> onB = ledBy(nodeB);
			assertEquals(predictedFor("node-a", "node-b"), onA, "node-a must lead what it weighs most for");
			assertEquals(predictedFor("node-b", "node-a"), onB, "node-b must lead what it weighs most for");
		});
	}

	private long releases ( String node, LeadershipReleaseReason reason ) {
		return kernelEvents.getOrDefault(node, List.of()).stream()
				.filter(e -> e instanceof BoundedContextEvent.LeadershipReleased released && released.reason() == reason)
				.count();
	}

	private long leadershipChanges ( String node ) {
		return kernelEvents.getOrDefault(node, List.of()).stream()
				.filter(e -> e instanceof BoundedContextEvent.LeadershipAcquired || e instanceof BoundedContextEvent.LeadershipReleased)
				.count();
	}

	private long acquisitions ( String node ) {
		return kernelEvents.getOrDefault(node, List.of()).stream()
				.filter(e -> e instanceof BoundedContextEvent.LeadershipAcquired)
				.count();
	}

	/**
	 * The point of the distribution: an instance joining one that leads everything takes over exactly
	 * the leases it weighs most for, so the twelve end up split between the two — and then nothing
	 * moves for as long as both stay up. Per backend, because the weight rides in the high-order
	 * priority the storage compares (a bigint on Postgres), and an order the storage gets wrong would
	 * show here as a lease that never settles.
	 */
	@ForEachBackend(requires = Capability.LEASE)
	public void aJoiningInstanceTakesOverTheLeasesItWeighsMostForAndThenNothingMoves ( ) {
		Mock nodeA = startInstance("node-a", 0, Duration.ZERO);
		assertEquals(new TreeSet<>(READ_MODELS), ledBy(nodeA), "alone, node-a leads everything from its synchronous start round");

		Mock nodeB = startInstance("node-b", 0, Duration.ZERO);
		awaitTheHashedAssignment(nodeA, nodeB);
		assertFalse(ledBy(nodeA).isEmpty(), "twelve leases over two instances: node-a keeps some");
		assertFalse(ledBy(nodeB).isEmpty(), "twelve leases over two instances: node-b gets some");

		long changesOnA = leadershipChanges("node-a");
		long changesOnB = leadershipChanges("node-b");
		await().during(TTL.multipliedBy(4)).atMost(TTL.multipliedBy(8)).until(
				() -> leadershipChanges("node-a") == changesOnA && leadershipChanges("node-b") == changesOnB);
		awaitTheHashedAssignment(nodeA, nodeB);
	}

	/**
	 * The stable identity: the leases of an instance that goes away spread over the rest, and when it
	 * comes back under the same physical name it wins back exactly what it had — so the instances that
	 * stayed up keep their own leases throughout.
	 */
	@Test
	public void anInstanceRestartingUnderItsNameWinsBackExactlyWhatItHad ( ) {
		Mock nodeA = startInstance("node-a", 0, Duration.ZERO);
		Mock nodeB = startInstance("node-b", 0, Duration.ZERO);
		awaitTheHashedAssignment(nodeA, nodeB);
		Set<String> nodeBHad = ledBy(nodeB);

		nodeB.terminate();
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(new TreeSet<>(READ_MODELS), ledBy(nodeA), "node-a must take over what node-b led"));

		Mock nodeBAgain = startInstance("node-b", 0, Duration.ZERO);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(nodeBHad, ledBy(nodeBAgain), "node-b, back under its name, must win back exactly what it had"));
	}

	/**
	 * Two instances starting together, the second within the first's hold-back: the leases the first
	 * won and the second weighs more for are handed over before anything ran on them, so neither
	 * instance ever steps down and every lease is acquired once.
	 */
	@Test
	public void instancesStartingTogetherSettleWithoutMovingWork ( ) {
		Duration holdBack = HEARTBEAT.multipliedBy(4);
		Mock nodeA = startInstance("node-a", 0, holdBack);
		Mock nodeB = startInstance("node-b", 0, holdBack);

		awaitTheHashedAssignment(nodeA, nodeB);
		assertEquals(0, releases("node-a", LeadershipReleaseReason.STEPPED_DOWN), "node-a must hand over nothing it was running");
		assertEquals(0, releases("node-b", LeadershipReleaseReason.STEPPED_DOWN), "node-b must hand over nothing it was running");
		assertEquals(READ_MODELS.size(), acquisitions("node-a") + acquisitions("node-b"), "every lease acquired exactly once");
	}

	/**
	 * The control for the scenario above: without a hold-back the first instance runs everything it
	 * won at once, so the second one's arrival is a step-down per lease it weighs more for — the moves
	 * the hold-back exists to avoid.
	 */
	@Test
	public void withoutAHoldBackInstancesStartingTogetherMoveWork ( ) {
		Mock nodeA = startInstance("node-a", 0, Duration.ZERO);
		Mock nodeB = startInstance("node-b", 0, Duration.ZERO);

		awaitTheHashedAssignment(nodeA, nodeB);
		assertEquals(predictedFor("node-b", "node-a").size(), releases("node-a", LeadershipReleaseReason.STEPPED_DOWN),
				"node-a ran everything first, and handed node-b its share");
	}

	/** During the hold-back a won lease runs nothing; once it has passed, the processors lead. */
	@Test
	public void aHeldBackLeaseRunsNothingUntilTheHoldBackHasPassed ( ) {
		Mock node = startInstance("node-a", 0, Duration.ofSeconds(1));
		assertEquals(Set.of(), ledBy(node), "nothing leads during the hold-back");
		assertTrue(eventStorage().getLeases().size() >= READ_MODELS.size(), "but the leases are held: " + eventStorage().getLeases());

		await().atMost(Duration.ofSeconds(10)).untilAsserted(
				() -> assertEquals(new TreeSet<>(READ_MODELS), ledBy(node), "after the hold-back the sole instance leads everything"));
	}

	/** A configured priority decides before any weight does: the preferred instance wins every lease. */
	@Test
	public void aConfiguredPriorityStillWinsEverything ( ) {
		Mock nodeA = startInstance("node-a", 0, Duration.ZERO);
		Mock nodeB = startInstance("node-b", 1, Duration.ZERO);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertEquals(new TreeSet<>(READ_MODELS), ledBy(nodeB), "the higher priority must lead everything");
			assertEquals(Set.of(), ledBy(nodeA), "the lower priority must lead nothing");
		});
	}

	/**
	 * A priority that does not fit beside the 32-bit weight is refused at build time under a balanced
	 * distribution, rather than overflowing into a different order; first-come takes any long.
	 */
	@Test
	public void aPriorityWiderThanAnIntIsRefusedUnderABalancedDistribution ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> newInstanceBuilder("node-a", Integer.MAX_VALUE + 1L, Duration.ZERO).build());
		assertTrue(refused.getMessage().contains("FIRST_COME"), refused.getMessage());

		Mock firstCome = newInstanceBuilder("node-a", Long.MAX_VALUE, Duration.ZERO)
				.leadershipDistribution(LeadershipDistribution.FIRST_COME).build();
		contexts.add(firstCome);
	}

	/**
	 * The weight that broke the tie is withheld after a yield: the instance that weighs most for a
	 * lease but keeps failing on it hands it to the healthy one, and does not take it back each ttl
	 * to fail again. Without that, the failing instance would yield once per ttl for as long as its
	 * trouble lasts.
	 */
	@Test
	public void aYieldedLeaseIsNotTakenBackFromTheHealthyInstance ( ) {
		FailableReadModel onA = new FailableReadModel();
		FailableReadModel onB = new FailableReadModel();
		BoundedContextBuilder<Mock> builderA = newInstanceBuilder("node-a", 0, Duration.ZERO);
		builderA.readmodel(onA).eventuallyConsistent();
		BoundedContextBuilder<Mock> builderB = newInstanceBuilder("node-b", 0, Duration.ZERO);
		builderB.readmodel(onB).eventuallyConsistent();
		Mock nodeA = builderA.build();
		contexts.add(nodeA);
		nodeA.start();
		Mock nodeB = builderB.build();
		contexts.add(nodeB);
		nodeB.start();

		// whichever instance weighs most for the failable read model's lease leads it; that one breaks
		String lease = eventStorage().getLeases().stream().map(Lease::leaseName)
				.filter(name -> name.contains("/" + FailableReadModel.class.getSimpleName() + "[")).findFirst().orElseThrow();
		boolean aWeighsMore = LeaderElector.weight("election/node-a", lease) > LeaderElector.weight("election/node-b", lease);
		String heavy = aWeighsMore ? "node-a" : "node-b";
		Mock heavyNode = aWeighsMore ? nodeA : nodeB;
		Mock lightNode = aWeighsMore ? nodeB : nodeA;
		await().atMost(Duration.ofSeconds(15)).until(() -> leads(heavyNode, FailableReadModel.class.getSimpleName()));
		( aWeighsMore ? onA : onB ).fail();

		heavyNode.event(new FirstDomainEvent("projected-by-the-healthy-instance"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(Map.of("projected-by-the-healthy-instance", 1), FailableReadModel.applications(),
						"the healthy instance must project what the failing one could not"));
		assertEquals(1, releases(heavy, LeadershipReleaseReason.PROCESSOR_FAILING), "yielded once");

		// several ttls later: the healthy instance still leads, the failing one has not taken it back
		await().during(TTL.multipliedBy(5)).atMost(TTL.multipliedBy(10)).until(
				() -> leads(lightNode, FailableReadModel.class.getSimpleName()) && releases(heavy, LeadershipReleaseReason.PROCESSOR_FAILING) == 1);
	}

	/** Over many leases the weights spread evenly: three identities each win about a third of 3000. */
	@Test
	public void theWeightsSpreadLeasesEvenly ( ) {
		List<String> identities = List.of("orders/node-1", "orders/node-2", "orders/node-3");
		Map<String,Integer> won = new HashMap<>();
		for ( int i = 0; i < 3000; i++ ) {
			String lease = "Orders/automation/Automation" + i;
			String winner = identities.stream()
					.max((x, y) -> Long.compare(LeaderElector.weight(x, lease), LeaderElector.weight(y, lease)))
					.orElseThrow();
			won.merge(winner, 1, Integer::sum);
		}
		won.forEach((identity, count) -> assertTrue(count > 900 && count < 1100, "an even third for " + identity + ": " + won));
	}

	private static boolean leads ( Mock context, String readModel ) {
		return context.processors().stream().anyMatch(p -> readModel.equals(p.name()) && p.leader());
	}

	/** A SHARED read model under a name of its own, so each is a lease of its own; it projects nothing worth asserting. */
	static class NamedSharedReadModel implements ReadModel<MockDomainEvent> {

		private final String name;

		NamedSharedReadModel ( String name ) {
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
			return EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
		}
	}

	/** A SHARED read model that can be made to fail transiently, counting its applications across instances. */
	static class FailableReadModel implements ReadModel<MockDomainEvent> {

		private static final Map<String,AtomicInteger> APPLICATIONS = new ConcurrentHashMap<>();

		private volatile boolean failing;

		void fail ( ) {
			this.failing = true;
		}

		static Map<String,Integer> applications ( ) {
			Map<String,Integer> snapshot = new HashMap<>();
			APPLICATIONS.forEach((value, count) -> snapshot.put(value, count.get()));
			return snapshot;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.SHARED;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( failing ) {
				throw new IllegalStateException("this instance cannot project '%s'".formatted(event.data()));
			}
			APPLICATIONS.computeIfAbsent(((FirstDomainEvent) event.data()).value(), v -> new AtomicInteger()).incrementAndGet();
		}
	}

	private String previousRetryInitial;
	private String previousRetryMax;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		kernelEvents.clear();
		FailableReadModel.APPLICATIONS.clear();
		// short retry pacing, so a transiently failing projector reaches the yield threshold within the
		// election intervals this test runs on (read when the bounded context is built)
		previousRetryInitial = System.setProperty("sliceworkz.eventmodeling.projector.retry.initial.ms", "50");
		previousRetryMax = System.setProperty("sliceworkz.eventmodeling.projector.retry.max.ms", "200");
	}

	@AfterEach
	void restoreRetryPacing ( ) {
		restore("sliceworkz.eventmodeling.projector.retry.initial.ms", previousRetryInitial);
		restore("sliceworkz.eventmodeling.projector.retry.max.ms", previousRetryMax);
	}

	private static void restore ( String property, String previous ) {
		if ( previous == null ) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

}
