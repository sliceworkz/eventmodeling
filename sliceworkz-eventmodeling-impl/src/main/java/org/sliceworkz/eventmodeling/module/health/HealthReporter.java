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
package org.sliceworkz.eventmodeling.module.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextHealth;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextState;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin.LagTarget;
import org.sliceworkz.eventmodeling.module.leadership.LeaderElector;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.observability.StorageHealth;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Assembles a bounded context's {@link BoundedContextHealth}: the storage's own report beside the checks
 * only the framework can make — see {@link BoundedContextHealth} and
 * {@link org.sliceworkz.eventmodeling.boundedcontext.HealthCapability} for what each one judges.
 * <p>
 * Everything but two parts is read from state the processors and the elector already keep, at once and
 * without a lock. The two that leave the process — the storage's report and the processor lag — are
 * bounded by the caller's timeout: the lag is started first, on a thread of its own, the storage's report
 * (which bounds itself) is asked next, and the lag is waited on for what is left. A lag measurement that
 * outlives its timeout stays in flight and the next call waits on that one, never on a second beside it, so
 * a storage that stopped answering is asked once — the rule the PostgreSQL storage's own database probe
 * keeps.
 */
public final class HealthReporter {

	/** The default of {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder#processorLagTolerance}. */
	public static final Duration DEFAULT_PROCESSOR_LAG_TOLERANCE = Duration.ofMinutes(1);

	private final String boundedContext;
	private final EventStorage storage;
	private final Duration lagTolerance;
	private final ProcessorLag processorLag;
	private final Map<ProcessorKind, Supplier<List<ProcessorStatus>>> processors;
	private final Map<ProcessorKind, Supplier<List<LagTarget>>> lagTargets;
	private final Supplier<List<AutomationStatus>> automations;
	private final Supplier<Map<String, Boolean>> inMemoryReadModelsProjected;
	private final Supplier<LeaderElector.Leadership> leadership;
	private final boolean monitored;
	private final Supplier<Long> listenerFailures;

	private final AtomicReference<LagMeasurement> lagMeasurement = new AtomicReference<>();

	/** a lag measurement started at {@code startedNanos} */
	private record LagMeasurement ( long startedNanos, CompletableFuture<List<ProcessorLag.Behind>> result ) { }

	/**
	 * @param boundedContext the context's name, which also names its streams
	 * @param storage the storage the context runs on
	 * @param lagTolerance how long an event may wait unread before its processor is behind
	 * @param processors the statuses of each projector-driven kind deployed here
	 * @param lagTargets the processors of each kind whose lag is measured
	 * @param automations the statuses of the automations deployed here
	 * @param inMemoryReadModelsProjected each in-memory read model and whether it has been projected once
	 * @param leadership this instance's part in leader election
	 * @param monitored whether the context has a monitoring listener
	 * @param listenerFailures how many deliveries in a row the listener has failed
	 */
	public HealthReporter ( String boundedContext, EventStorage storage, Duration lagTolerance,
			Map<ProcessorKind, Supplier<List<ProcessorStatus>>> processors,
			Map<ProcessorKind, Supplier<List<LagTarget>>> lagTargets,
			Supplier<List<AutomationStatus>> automations,
			Supplier<Map<String, Boolean>> inMemoryReadModelsProjected,
			Supplier<LeaderElector.Leadership> leadership,
			boolean monitored,
			Supplier<Long> listenerFailures ) {
		this.boundedContext = boundedContext;
		this.storage = storage;
		this.lagTolerance = lagTolerance;
		this.processors = new EnumMap<>(processors);
		this.lagTargets = new EnumMap<>(lagTargets);
		this.automations = automations;
		this.inMemoryReadModelsProjected = inMemoryReadModelsProjected;
		this.leadership = leadership;
		this.monitored = monitored;
		this.listenerFailures = listenerFailures;
		Map<ProcessorKind, EventStreamId> streams = new EnumMap<>(ProcessorKind.class);
		streams.put(ProcessorKind.READ_MODEL, BoundedContextStreams.domain(boundedContext));
		streams.put(ProcessorKind.PUBLISHER, BoundedContextStreams.domain(boundedContext));
		streams.put(ProcessorKind.POLICY, BoundedContextStreams.domain(boundedContext));
		streams.put(ProcessorKind.TRANSLATOR, BoundedContextStreams.inbound(boundedContext));
		streams.put(ProcessorKind.DISPATCHER, BoundedContextStreams.outbound(boundedContext));
		this.processorLag = new ProcessorLag(storage, streams);
	}

	/**
	 * The context's health, checked now.
	 *
	 * @param state where the context is in its lifecycle
	 * @param timeout how long to wait for what leaves the process
	 * @return the health
	 */
	public BoundedContextHealth health ( BoundedContextState state, Duration timeout ) {
		Objects.requireNonNull(timeout, "timeout must not be null");
		if ( timeout.isNegative() || timeout.isZero() ) {
			throw new IllegalArgumentException("health timeout must be positive, but was " + timeout);
		}
		long deadlineNanos = System.nanoTime() + timeout.toNanos();
		boolean running = state == BoundedContextState.RUNNING;
		// measured only while the processors run, and only where there are any: parked, every one is behind on purpose
		boolean projectorsDeployed = processors.values().stream().anyMatch(supplier -> !supplier.get().isEmpty());
		LagMeasurement lag = running && projectorsDeployed ? currentLagMeasurement() : null;

		StorageHealth storageHealth = storageHealth(timeout);

		List<HealthCheck> checks = new ArrayList<>();
		checks.add(lifecycleCheck(state));
		inMemoryReadModelsCheck(state).ifPresent(checks::add);
		processorChecks(running, checks);
		automationsCheck(running).ifPresent(checks::add);
		if ( lag != null ) {
			checks.add(lagCheck(lag, deadlineNanos));
		}
		checks.add(leadershipCheck(running));
		if ( monitored ) {
			checks.add(listenerCheck());
		}
		return new BoundedContextHealth(boundedContext, state, storageHealth, checks);
	}

	/** asked of the storage, which bounds itself; whatever it throws becomes its report rather than ours */
	private StorageHealth storageHealth ( Duration timeout ) {
		try {
			return storage.health(timeout);
		} catch ( RuntimeException e ) {
			return new StorageHealth(storage.name(), List.of(HealthCheck.of("health", HealthStatus.UNKNOWN,
				"the storage failed to report its health: " + Objects.toString(e.getMessage(), e.getClass().getName()), Map.of())));
		}
	}

	private static HealthCheck lifecycleCheck ( BoundedContextState state ) {
		Map<String, Object> details = Map.of("state", state.name());
		return switch ( state ) {
			case RUNNING -> HealthCheck.up(BoundedContextHealth.LIFECYCLE, details);
			case BUILT -> HealthCheck.of(BoundedContextHealth.LIFECYCLE, HealthStatus.DOWN, "built and never started", details);
			case STARTING -> HealthCheck.of(BoundedContextHealth.LIFECYCLE, HealthStatus.DOWN,
				"starting: waiting for the in-memory read models to be projected", details);
			case STOPPED -> HealthCheck.of(BoundedContextHealth.LIFECYCLE, HealthStatus.DOWN, "stopped: its processors are parked", details);
			case TERMINATED -> HealthCheck.of(BoundedContextHealth.LIFECYCLE, HealthStatus.DOWN, "terminated", details);
		};
	}

	private Optional<HealthCheck> inMemoryReadModelsCheck ( BoundedContextState state ) {
		Map<String, Boolean> projected = inMemoryReadModelsProjected.get();
		if ( projected.isEmpty() ) {
			return Optional.empty();
		}
		List<String> outstanding = projected.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
		Map<String, Object> details = Map.of("total", projected.size(), "projected", projected.size() - outstanding.size());
		if ( state != BoundedContextState.RUNNING || outstanding.isEmpty() ) {
			return Optional.of(HealthCheck.up(BoundedContextHealth.IN_MEMORY_READ_MODELS, details));
		}
		return Optional.of(HealthCheck.of(BoundedContextHealth.IN_MEMORY_READ_MODELS, HealthStatus.DEGRADED,
			"started before these were projected, still catching up in the background: " + String.join(", ", outstanding), details));
	}

	private void processorChecks ( boolean running, List<HealthCheck> checks ) {
		for ( ProcessorKind kind : ProcessorKind.values() ) {
			Supplier<List<ProcessorStatus>> supplier = processors.get(kind);
			List<ProcessorStatus> statuses = supplier == null ? List.of() : supplier.get();
			if ( statuses.isEmpty() ) {
				continue;
			}
			checks.add(componentsCheck(checkName(kind), running, statuses.stream()
				.map(s -> new Component(s.name(), s.running(), s.leader(), s.consecutiveFailedRuns() > 0, s.stoppedBy() != null))
				.toList()));
		}
	}

	private Optional<HealthCheck> automationsCheck ( boolean running ) {
		List<AutomationStatus> statuses = automations.get();
		if ( statuses.isEmpty() ) {
			return Optional.empty();
		}
		return Optional.of(componentsCheck(BoundedContextHealth.AUTOMATIONS, running, statuses.stream()
			.map(s -> new Component(s.automation(), s.running(), s.leader(), s.consecutiveFailedBatches() > 0, s.stoppedBy() != null))
			.toList()));
	}

	/** a processor or automation as its kind's check judges it */
	private record Component ( String name, boolean running, boolean leader, boolean failing, boolean stoppedOnFailure ) { }

	/**
	 * Retired on a failure and retrying are degraded, an operator's stop is counted and fine; nothing is
	 * judged before the context runs, since every component is parked on purpose until then.
	 */
	private static HealthCheck componentsCheck ( String check, boolean running, List<Component> components ) {
		List<String> stoppedOnFailure = components.stream().filter(c -> !c.running() && c.stoppedOnFailure()).map(Component::name).toList();
		List<String> stoppedByOperator = components.stream().filter(c -> !c.running() && !c.stoppedOnFailure()).map(Component::name).toList();
		List<String> retrying = components.stream().filter(c -> c.running() && c.failing()).map(Component::name).toList();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("total", components.size());
		details.put("running", components.stream().filter(Component::running).count());
		details.put("leading", components.stream().filter(c -> c.running() && c.leader()).count());
		details.put("stoppedOnFailure", stoppedOnFailure.size());
		details.put("stoppedByOperator", running ? stoppedByOperator.size() : 0);
		details.put("retrying", retrying.size());
		if ( !running || (stoppedOnFailure.isEmpty() && retrying.isEmpty()) ) {
			return HealthCheck.up(check, details);
		}
		List<String> problems = new ArrayList<>();
		if ( !stoppedOnFailure.isEmpty() ) {
			problems.add("stopped on a failure, waiting for an operator: " + String.join(", ", stoppedOnFailure));
		}
		if ( !retrying.isEmpty() ) {
			problems.add("retrying after failures: " + String.join(", ", retrying));
		}
		return HealthCheck.of(check, HealthStatus.DEGRADED, String.join("; ", problems), details);
	}

	private static String checkName ( ProcessorKind kind ) {
		return switch ( kind ) {
			case READ_MODEL -> BoundedContextHealth.READ_MODELS;
			case TRANSLATOR -> BoundedContextHealth.TRANSLATORS;
			case DISPATCHER -> BoundedContextHealth.DISPATCHERS;
			case PUBLISHER -> BoundedContextHealth.PUBLISHERS;
			case POLICY -> BoundedContextHealth.POLICIES;
		};
	}

	/** the measurement still in flight, or a new one when there is none */
	private LagMeasurement currentLagMeasurement ( ) {
		while ( true ) {
			LagMeasurement current = lagMeasurement.get();
			if ( current != null && !current.result().isDone() ) {
				return current;
			}
			LagMeasurement fresh = new LagMeasurement(System.nanoTime(), new CompletableFuture<>());
			if ( lagMeasurement.compareAndSet(current, fresh) ) {
				List<LagTarget> targets = new ArrayList<>();
				lagTargets.values().forEach(supplier -> targets.addAll(supplier.get()));
				Thread.ofVirtual().name("health-lag-" + boundedContext).start(() -> {
					try {
						fresh.result().complete(processorLag.measure(targets, Instant.now()));
					} catch ( Throwable t ) {
						// completed whatever was thrown: one left incomplete would be waited on by every later call
						fresh.result().completeExceptionally(t);
						if ( t instanceof Error error ) {
							throw error;
						}
					}
				});
				return fresh;
			}
		}
	}

	private HealthCheck lagCheck ( LagMeasurement measurement, long deadlineNanos ) {
		List<ProcessorLag.Behind> behind;
		try {
			behind = measurement.result().get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch ( TimeoutException e ) {
			return HealthCheck.of(BoundedContextHealth.PROCESSOR_LAG, HealthStatus.UNKNOWN,
				"the storage has not answered the lag measurement in %dms".formatted(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - measurement.startedNanos())), Map.of());
		} catch ( ExecutionException e ) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			return HealthCheck.of(BoundedContextHealth.PROCESSOR_LAG, HealthStatus.UNKNOWN,
				"the lag could not be measured: " + Objects.toString(cause.getMessage(), cause.getClass().getName()), Map.of());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			return HealthCheck.of(BoundedContextHealth.PROCESSOR_LAG, HealthStatus.UNKNOWN, "interrupted while measuring the lag", Map.of());
		}
		List<ProcessorLag.Behind> beyond = behind.stream().filter(b -> b.oldestUnreadWaited().compareTo(lagTolerance) > 0).toList();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("toleranceMillis", lagTolerance.toMillis());
		details.put("behind", behind.size());
		details.put("beyondTolerance", beyond.size());
		details.put("oldestUnreadMillis", behind.stream().mapToLong(b -> b.oldestUnreadWaited().toMillis()).max().orElse(0));
		if ( beyond.isEmpty() ) {
			return HealthCheck.up(BoundedContextHealth.PROCESSOR_LAG, details);
		}
		return HealthCheck.of(BoundedContextHealth.PROCESSOR_LAG, HealthStatus.DEGRADED,
			"behind for longer than %ds: %s".formatted(lagTolerance.toSeconds(), String.join(", ", beyond.stream()
				.map(b -> "%s %s (oldest unread event waiting %ds)".formatted(b.target().kind().name().toLowerCase(), b.target().name(), b.oldestUnreadWaited().toSeconds()))
				.toList())), details);
	}

	private HealthCheck leadershipCheck ( boolean running ) {
		LeaderElector.Leadership leadership = this.leadership.get();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("leasesSupported", leadership.leasesSupported());
		details.put("leases", leadership.leases());
		details.put("held", leadership.held());
		details.put("unansweredForMillis", leadership.unansweredForMillis());
		if ( !leadership.leasesSupported() && leadership.leases() > 0 ) {
			return HealthCheck.of(BoundedContextHealth.LEADERSHIP, HealthStatus.UP,
				"the storage holds no leases: every leader-only processor runs on this instance, and a second instance would duplicate its work", details);
		}
		if ( running && leadership.unansweredForMillis() > leadership.ttl().toMillis() ) {
			return HealthCheck.of(BoundedContextHealth.LEADERSHIP, HealthStatus.DEGRADED,
				"the storage has not answered a lease request for %ds: this instance can neither keep nor take over leadership"
					.formatted(leadership.unansweredForMillis() / 1000), details);
		}
		return HealthCheck.up(BoundedContextHealth.LEADERSHIP, details);
	}

	private HealthCheck listenerCheck ( ) {
		long failures = listenerFailures.get();
		Map<String, Object> details = Map.of("consecutiveFailures", failures);
		return failures == 0
			? HealthCheck.up(BoundedContextHealth.MONITORING_LISTENER, details)
			: HealthCheck.of(BoundedContextHealth.MONITORING_LISTENER, HealthStatus.DEGRADED,
				"its last %d deliveries failed: those monitoring events are lost, and the work they report is unaffected".formatted(failures), details);
	}

}
