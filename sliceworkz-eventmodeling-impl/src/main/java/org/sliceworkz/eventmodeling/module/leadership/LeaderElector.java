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

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.LeadershipReleaseReason;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.threading.Processor;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;
import org.sliceworkz.eventmodeling.module.threading.ProcessorInstanceMode;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseRequest;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseResponse;

/**
 * Elects, per leader-only processor, the one instance of a bounded context that runs it — by holding
 * a named lease in the event storage, renewed on a heartbeat from a thread of its own.
 *
 * <h2>Why per processor</h2>
 * Each lease is named by the processor's {@link ProcessorIdentification} — the same string that keys
 * its bookmark, so the lease guards exactly that bookmark's writer. Instances contend only for the
 * processors they have deployed: a deployment that puts automation X on one instance and automation Y
 * on another simply has each instance win the leases nobody else wants, where a single per-context
 * lease would strand every processor the winning instance does not carry. With equal priorities and
 * identical deployments, whoever starts first wins everything; a priority makes the preferred
 * instance win everything back.
 *
 * <h2>Election is never on the processing path</h2>
 * Leadership is a {@code volatile} field on each processor, flipped through
 * {@link Processor#instanceMode}; the processors read it at the top of every loop pass and never wait
 * for a lease call. The elector renews in the background — a leader processes continuously across
 * renewals, and no batch, projection or event query is delayed by election traffic.
 *
 * <h2>The safety rule</h2>
 * Expiry is judged on the storage's clock; this elector only ever measures durations on its own. A
 * leadership it cannot re-confirm is given up <em>before</em> the storage would hand the lease to
 * somebody else: after {@code ttl - heartbeatInterval} without a confirmed renewal the processor is
 * demoted locally (see {@link LeadershipReleaseReason#RENEWAL_FAILED}), while a challenger acquires
 * no earlier than {@code ttl}. Demote-before-takeover, so two leaders do not overlap — except for a
 * process paused beyond its ttl, which no lease can prevent and the fencing token exists to expose.
 *
 * <h2>Step-down (fail-back)</h2>
 * A renewal answered {@code LEADER_STEP_DOWN_REQUESTED} demotes the processor at once — it finishes
 * its current batch and parks — and releases the lease one heartbeat later, giving the batch a full
 * interval to complete before another instance may begin. The storage never revokes a live lease
 * itself, so a step-down is always this instance's own, orderly act.
 *
 * <h2>Storages without leases</h2>
 * A storage whose {@code requestLease} throws {@link UnsupportedOperationException} gets the behaviour
 * this framework had before leader election existed: every leader-only processor is promoted on this
 * instance, with one WARN saying that a second instance would duplicate work. In-memory storages
 * support leases, so a single-process deployment wins everything trivially rather than falling back.
 */
public class LeaderElector implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(LeaderElector.class);

	/** A leader-only processor and the identification whose string names its lease. */
	public record Electable ( ProcessorIdentification identification, Processor processor ) { }

	private final String boundedContext;
	private final EventStorage eventStorage;
	private final String owner;
	private final long priority;
	private final Duration heartbeatInterval;
	private final Duration ttl;
	private final List<LeaseState> leases;
	private final BoundedContextEventEmitter eventEmitter;

	private final Object sleeper = new Object();
	private volatile boolean terminating;
	private volatile boolean paused;
	private volatile boolean leasesUnsupported;
	private boolean threadStarted; // guarded by synchronized(this)

	private static final class LeaseState {
		final Electable electable;
		boolean leader;
		long lastConfirmedMs;
		boolean releaseNextRound;

		LeaseState ( Electable electable ) {
			this.electable = electable;
		}

		String leaseName ( ) {
			return electable.identification().toString();
		}
	}

	public LeaderElector ( String boundedContext, EventStorage eventStorage, String ownerBase, long priority,
			Duration heartbeatInterval, Duration ttl, List<Electable> electables, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.eventStorage = eventStorage;
		// The owner must identify THIS elector, not this JVM: Instance.process() is computed once per
		// process, so two bounded-context instances in one JVM (a test, or a deliberate co-located
		// deployment) would otherwise share an owner id and each mistake the other's lease for its own —
		// both leaders, exactly what the lease exists to prevent. The random suffix costs nothing: a
		// fresh contender identity per elector is wanted anyway, since fail-back rides on priority and
		// never on owner identity, and a terminated elector's leases are released or expire.
		this.owner = ownerBase + "-" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE);
		this.priority = priority;
		this.heartbeatInterval = heartbeatInterval;
		this.ttl = ttl;
		this.leases = electables.stream().map(LeaseState::new).toList();
		this.eventEmitter = eventEmitter;
	}

	/**
	 * Runs one synchronous election round and starts the heartbeat thread (once). Called from the
	 * bounded context's own {@code start()}, so a single instance — or the preferred one on a quiet
	 * deployment — is already leader before its processors take their first loop pass, instead of a
	 * heartbeat interval later.
	 */
	public synchronized void start ( ) {
		paused = false;
		electOnce();
		if ( !threadStarted && !leasesUnsupported && !leases.isEmpty() ) {
			threadStarted = true;
			Thread.ofVirtual().name("leader-elector/" + boundedContext).start(this);
		}
	}

	/**
	 * Demotes everything and releases the held leases, so another instance takes over promptly; the
	 * elector pauses until the next {@link #start()}. This is what a stopped bounded context must do —
	 * parked processors holding leases would stall the whole deployment.
	 */
	public synchronized void stop ( ) {
		paused = true;
		releaseEverything(LeadershipReleaseReason.STOPPED);
	}

	/**
	 * Releases the held leases and ends the heartbeat thread, for a context going down. No
	 * {@code LeadershipReleased} events: {@code BoundedContextStopping} has already said it for
	 * everything at once (the same asymmetry {@code AutomationStopped} keeps at shutdown).
	 */
	public void terminate ( ) {
		terminating = true;
		synchronized ( sleeper ) {
			sleeper.notify();
		}
		synchronized ( this ) {
			releaseEverything(null);
		}
	}

	@Override
	public void run ( ) {
		LOGGER.debug("leader elector of '{}' running, {} lease(s), heartbeat {}, ttl {}", boundedContext, leases.size(), heartbeatInterval, ttl);
		while ( !terminating && !leasesUnsupported ) {
			synchronized ( sleeper ) {
				if ( terminating ) {
					break;
				}
				try {
					sleeper.wait(heartbeatInterval.toMillis());
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			if ( !terminating && !paused ) {
				synchronized ( this ) {
					electOnce();
				}
			}
		}
		LOGGER.debug("leader elector of '{}' ended", boundedContext);
	}

	/**
	 * One election round: request (or release) every lease and apply the outcome to its processor.
	 * Guarded by {@code synchronized(this)} at every caller, so a round from the heartbeat thread and
	 * one from {@code start()}/{@code stop()} never interleave.
	 */
	private void electOnce ( ) {
		if ( leasesUnsupported ) {
			return;
		}
		for ( LeaseState state : leases ) {
			try {
				if ( state.releaseNextRound ) {
					// second half of a step-down: the demoted processor has had a full heartbeat
					// interval to finish its batch; hand the lease over now
					state.releaseNextRound = false;
					eventStorage.releaseLease(state.leaseName(), owner);
					LOGGER.info("'{}' handed its lease over after stepping down", state.leaseName());
					continue;
				}

				if ( state.electable.processor().stoppedItself() ) {
					// The processor retired itself -- a projector on a projection failure, an automation
					// through STOP_AUTOMATION -- and does no work however the election goes. Renewing its
					// lease from here would hold that work off every healthy instance for as long as this
					// process lives: the same "parked processors holding leases would stall the whole
					// deployment" principle stop() states, applied per processor. Release what we hold and
					// stay out of the election -- also as a contender, so a self-stopped high-priority
					// instance does not step down whoever took over -- until the processor is started
					// again, which clears the flag and puts us back in the race next round.
					if ( state.leader ) {
						LOGGER.warn("'{}' stopped itself, releasing its lease so a healthy instance can take over", state.leaseName());
						demote(state, LeadershipReleaseReason.PROCESSOR_STOPPED);
						// a throw here lands in the catch below and is retried next heartbeat; state.leader
						// is already false by then, so worst case the lease expires on the storage's ttl
						eventStorage.releaseLease(state.leaseName(), owner);
					}
					continue;
				}

				LeaseResponse response = eventStorage.requestLease(new LeaseRequest(state.leaseName(), owner, priority, ttl));
				switch ( response.status() ) {
					case LEADER -> {
						state.lastConfirmedMs = System.currentTimeMillis();
						if ( !state.leader ) {
							promote(state, response.fencingToken());
						}
					}
					case LEADER_STEP_DOWN_REQUESTED -> {
						state.lastConfirmedMs = System.currentTimeMillis();
						if ( state.leader ) {
							LOGGER.info("a higher-priority contender is waiting for '{}', stepping down", state.leaseName());
							demote(state, LeadershipReleaseReason.STEPPED_DOWN);
							state.releaseNextRound = true;
						} else {
							// asked to step down from a lease we never told the processor about
							// (promotion and step-down in between rounds): nothing ran, release at once
							eventStorage.releaseLease(state.leaseName(), owner);
						}
					}
					case STANDBY -> {
						if ( state.leader ) {
							// somebody else holds it, so ours lapsed without us noticing in time
							demote(state, LeadershipReleaseReason.LOST);
						}
					}
				}

			} catch ( UnsupportedOperationException noLeases ) {
				fallBackToAlwaysLeader();
				return;
			} catch ( RuntimeException e ) {
				// A failed round confirms nothing. The safety rule: give up a leadership that cannot be
				// re-confirmed before the storage's ttl can hand the lease elsewhere -- one heartbeat
				// early, since a challenger acquires no sooner than ttl on the storage clock.
				long unconfirmedForMs = System.currentTimeMillis() - state.lastConfirmedMs;
				long localDeadlineMs = Math.max(heartbeatInterval.toMillis(), ttl.toMillis() - heartbeatInterval.toMillis());
				if ( state.leader && unconfirmedForMs >= localDeadlineMs ) {
					LOGGER.warn("could not renew the lease of '{}' for {} ms, demoting rather than assume a leadership that cannot be proven: {}",
							state.leaseName(), unconfirmedForMs, e.getMessage());
					demote(state, LeadershipReleaseReason.RENEWAL_FAILED);
				} else {
					LOGGER.warn("lease request for '{}' failed, retrying on the next heartbeat: {}", state.leaseName(), e.getMessage());
				}
			}
		}
	}

	private void promote ( LeaseState state, long fencingToken ) {
		state.leader = true;
		state.electable.processor().instanceMode(ProcessorInstanceMode.LEADER);
		LOGGER.info("'{}' elected leader on this instance (fencing token {})", state.leaseName(), fencingToken);
		eventEmitter.emit(new BoundedContextEvent.LeadershipAcquired(boundedContext,
				state.electable.identification().type(), state.electable.identification().id(), fencingToken));
	}

	private void demote ( LeaseState state, LeadershipReleaseReason reason ) {
		state.leader = false;
		state.electable.processor().instanceMode(ProcessorInstanceMode.STANDBY);
		LOGGER.info("'{}' no longer leader on this instance ({})", state.leaseName(), reason);
		eventEmitter.emit(new BoundedContextEvent.LeadershipReleased(boundedContext,
				state.electable.identification().type(), state.electable.identification().id(), reason));
	}

	/**
	 * The behaviour this framework had before leader election: everything runs here. Chosen once, on
	 * the first {@code UnsupportedOperationException}, for a third-party storage predating leases —
	 * failing the boot over a coordination feature the storage cannot offer would be worse, but a
	 * second instance on such a storage duplicates every item, so it is said out loud.
	 */
	private void fallBackToAlwaysLeader ( ) {
		leasesUnsupported = true;
		LOGGER.warn("event storage '{}' does not support leases: no leader election, every leader-only processor runs on this instance. "
				+ "A second instance of context '{}' on this storage will duplicate work.", eventStorage.name(), boundedContext);
		for ( LeaseState state : leases ) {
			if ( !state.leader ) {
				promote(state, 0);
			}
		}
	}

	/**
	 * Demotes every leader and releases its lease. With a null reason the demotion is silent — the
	 * shutdown path, where {@code BoundedContextStopping} already covers it.
	 */
	private void releaseEverything ( LeadershipReleaseReason reason ) {
		if ( leasesUnsupported ) {
			return;
		}
		for ( LeaseState state : leases ) {
			state.releaseNextRound = false;
			if ( state.leader ) {
				state.leader = false;
				state.electable.processor().instanceMode(ProcessorInstanceMode.STANDBY);
				if ( reason != null ) {
					eventEmitter.emit(new BoundedContextEvent.LeadershipReleased(boundedContext,
							state.electable.identification().type(), state.electable.identification().id(), reason));
				}
				try {
					eventStorage.releaseLease(state.leaseName(), owner);
				} catch ( RuntimeException e ) {
					// best effort: an unreleased lease simply expires after its ttl
					LOGGER.debug("could not release lease '{}', it will expire on its own: {}", state.leaseName(), e.getMessage());
				}
			}
		}
	}

}
