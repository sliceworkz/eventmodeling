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
package org.sliceworkz.eventmodeling.boundedcontext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.observability.StorageHealth;

/**
 * A bounded context's health at one moment, on this instance: what {@link HealthCapability#health()}
 * answers, for a health endpoint to render.
 * <p>
 * Two halves. {@link #storage()} is the event storage's own report — its database, its channels, its
 * visibility barrier, its pools — as the eventstore judges it. {@link #checks()} is what only the framework
 * can judge: where the context is in its lifecycle, whether its processors are running, retrying or
 * behind, whether this instance can take part in leader election, and whether its monitoring listener is
 * losing events. Both use the eventstore's {@link HealthCheck} and {@link HealthStatus}, so a health
 * endpoint renders one vocabulary. The {@link #status()} is derived — the worst of both halves — and never
 * stated beside them, so the two cannot disagree.
 * <p>
 * What a status <em>means</em> for the application is the caller's decision, exactly as for the storage:
 * {@link HealthStatus#DOWN} is a context that cannot serve, {@link HealthStatus#DEGRADED} one that serves
 * while something it relies on does not work — a processor retired on a failure, a read model falling
 * behind — and restarting the process fixes none of what is reported degraded. A readiness probe usually
 * waits for {@link BoundedContextState#RUNNING} and a database that answers; a liveness probe rarely has a
 * reason to look here at all.
 *
 * @param boundedContext the bounded context's name
 * @param state where the context is in its lifecycle
 * @param storage the event storage's own report
 * @param checks what the framework checked, in a stable order; only the checks that apply to this
 *        instance are present — a context deploying no translator reports no {@link #TRANSLATORS}
 */
public record BoundedContextHealth ( String boundedContext, BoundedContextState state, StorageHealth storage, List<HealthCheck> checks ) {

	/** Where the context is in its lifecycle: {@link HealthStatus#UP} while {@link BoundedContextState#RUNNING}, {@link HealthStatus#DOWN} otherwise. */
	public static final String LIFECYCLE = "lifecycle";

	/**
	 * Whether the in-memory read models have been projected once: reported when there are any, and
	 * {@link HealthStatus#DEGRADED} while a context that started without waiting for them all — its startup
	 * wait timed out — still has some catching up.
	 */
	public static final String IN_MEMORY_READ_MODELS = "inMemoryReadModels";

	/** The read model projectors, judged as described on {@link HealthCapability}. */
	public static final String READ_MODELS = "readModels";

	/** The automations, judged as described on {@link HealthCapability}. */
	public static final String AUTOMATIONS = "automations";

	/** The translators, judged as described on {@link HealthCapability}. */
	public static final String TRANSLATORS = "translators";

	/** The dispatchers, judged as described on {@link HealthCapability}. */
	public static final String DISPATCHERS = "dispatchers";

	/** The publishers, judged as described on {@link HealthCapability}. */
	public static final String PUBLISHERS = "publishers";

	/** The policies, judged as described on {@link HealthCapability}. */
	public static final String POLICIES = "policies";

	/**
	 * Whether the projector-driven processors running here have read what their stream holds:
	 * {@link HealthStatus#DEGRADED} for one whose oldest unread event has waited longer than the builder's
	 * {@link BoundedContextBuilder#processorLagTolerance(java.time.Duration) processorLagTolerance}.
	 */
	public static final String PROCESSOR_LAG = "processorLag";

	/** Whether this instance takes part in leader election: {@link HealthStatus#DEGRADED} while the storage does not answer its lease requests. */
	public static final String LEADERSHIP = "leadership";

	/** Whether the monitoring listener takes the events it is handed: reported when there is one, {@link HealthStatus#DEGRADED} while its deliveries fail. */
	public static final String MONITORING_LISTENER = "monitoringListener";

	/**
	 * @throws IllegalArgumentException for two checks with one name
	 */
	public BoundedContextHealth {
		Objects.requireNonNull(boundedContext, "boundedContext must not be null");
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(storage, "storage must not be null");
		checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
		if ( checks.stream().map(HealthCheck::name).distinct().count() != checks.size() ) {
			throw new IllegalArgumentException("bounded context '%s' reports two health checks with one name: %s"
				.formatted(boundedContext, checks.stream().map(HealthCheck::name).toList()));
		}
	}

	/**
	 * The context's status: the worst of the storage's status and the framework's checks.
	 *
	 * @return the status
	 */
	public HealthStatus status ( ) {
		List<HealthStatus> statuses = new ArrayList<>();
		statuses.add(storage.status());
		checks.forEach(check -> statuses.add(check.status()));
		return HealthStatus.worst(statuses);
	}

	/**
	 * One of the framework's checks by name.
	 *
	 * @param name the check's name, one of the constants on this record
	 * @return the check, or empty when it does not apply to this instance
	 */
	public Optional<HealthCheck> check ( String name ) {
		return checks.stream().filter(c -> c.name().equals(name)).findFirst();
	}

}
