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
package org.sliceworkz.eventmodeling.readmodels;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.TracingScope;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventSource;

/**
 * An in-memory read model that holds only the keys being read: a key is loaded from the event store
 * the first time it is read, kept current by a projector from then on, and let go when the
 * {@link CachePolicy} needs the room or it has not been read for a while.
 *
 * <p><b>The gap it fills.</b> An eventually consistent in-memory read model holds every key there is —
 * fine for a thousand accounts, not for ten million. A live model holds nothing, and replays a key's
 * history on every read. This read model is the live read of a key, kept instead of thrown away: the
 * first read of customer 23 replays customer 23's events, and every read after it is a lookup in
 * memory, current within the projector's lag. Memory is bounded by the policy, not by the stream.
 *
 * <pre>{@code
 * public class CustomerSummaryReadModel extends CachingReadModel<ShopEvent, CustomerId, CustomerSummary> {
 *
 *     public CustomerSummaryReadModel ( ) {
 *         super(CacheKeys.byEntity(CUSTOMER), CachePolicy.maxEntries(5_000).expireAfterAccess(Duration.ofMinutes(30)));
 *     }
 *
 *     public EventFilter eventFilter ( ) {
 *         return EventFilter.forTypes(CustomerRegistered.class, OrderPlaced.class);
 *     }
 *
 *     protected CustomerSummary initialState ( CustomerId id ) {
 *         return CustomerSummary.unknown(id);
 *     }
 *
 *     protected CustomerSummary apply ( CustomerSummary summary, Event<ShopEvent> event ) {
 *         return switch ( event.data() ) {
 *             case CustomerRegistered r -> summary.registered(r.name());
 *             case OrderPlaced o        -> summary.ordered(o.total());
 *             default                   -> summary;
 *         };
 *     }
 * }
 *
 * builder.readmodel(new CustomerSummaryReadModel()).eventuallyConsistent();
 * CustomerSummary summary = shop.read(CustomerSummaryReadModel.class).get(customerId);
 * }</pre>
 *
 * <p><b>How an entry is kept correct.</b> The projector reads every relevant event from the stream head
 * on and folds each into the entries of the keys it is about ({@link CacheKeys#keysOf}); an event about
 * no key held is read and dropped. A miss replays the key's events ({@link CacheKeys#narrow}) up to the
 * stream head and inserts the entry — and the one thing to get right is the events appended while that
 * replay ran. The projector may already have passed some of them, at a moment the key was not held, so
 * the insert compares the head the replay read up to with how far the projector has delivered, and when
 * the projector is ahead it reads the difference before inserting, with the projector held for that
 * moment. Every entry records the last event folded into it and folds nothing at or before it, so an
 * event the replay read and the projector then delivers is counted once. The result: a read that hits is
 * as current as an eventually consistent read model, and one that misses as current as a live one.
 *
 * <p><b>What it costs.</b>
 * <ul>
 *   <li>A miss is a live read of the key: on PostgreSQL an index lookup by tag, in memory a scan of the
 *       whole log. The read model pays where reads repeat the same keys — the customers someone is working
 *       with this morning — and is a live model with a projector beside it where reads are spread evenly
 *       over far more keys than it holds. {@link #statistics()} says which, through hits against misses.</li>
 *   <li>The projector reads every relevant event appended, held or not, as any eventually consistent read
 *       model does, but folds only those for held keys.</li>
 *   <li>Reads that miss the same key at once make one load: the later ones wait for it and answer what
 *       it loaded ({@link CacheStatistics#sharedLoads()}).</li>
 *   <li>Every read is observed as an {@link org.sliceworkz.eventmodeling.observability.Observation.CachedRead},
 *       answering a hit, a load or a shared load, so a metrics binding can chart the hit rate.</li>
 *   <li>Nothing is warmed: after a start, and after the projector is restarted, every key is a miss
 *       once. Starting does not replay the history either — the projector starts at the stream head,
 *       since nothing is held that the history could update.</li>
 * </ul>
 *
 * <p><b>Rules for a subclass.</b>
 * <ul>
 *   <li>{@code STATE} is immutable, and {@link #apply} a pure function of {@code (state, event)} that
 *       returns the next state, as for {@link PublishingReadModel}: a reader holds the state it was given
 *       while the projector folds the next event, on another thread. Where a copy per event costs too
 *       much — a state of thousands of entries, folded from thousands of events on a load — the state may
 *       instead guard its own reads and writes, and {@link #apply} change it in place and return it. Its
 *       readers then see it move on after they were given it, past the reference
 *       {@link #result(Object)} answered with.</li>
 *   <li>The key's events must carry what {@link CacheKeys} reads — with {@link CacheKeys#byEntity}, the
 *       entity's tag. An event that is about a key without saying so is never folded into it.</li>
 *   <li>Registered as an instance, {@code eventuallyConsistent()}: a live registration is refused at
 *       {@code build()}, since a read model projected per read holds nothing between reads. Read it by its
 *       class, {@code read(X.class)}, which answers the registered instance.</li>
 * </ul>
 *
 * <p><b>Read your own write.</b> {@link #result(Object)} answers the state with the last event folded into
 * it, so a caller that executed a command checks {@code result.includes(write)}; {@link #getIncluding}
 * loads the key again when a held entry does not include the write yet. A caller that has no reference
 * — a request that may follow its own write — reads with {@link #getCurrent}, which is as current as a
 * live read at the cost of a head lookup.
 *
 * <p><b>When the projector stops</b> — a failure it retries, one that retired it, an operator stop, the
 * context stopping — nothing it would have kept current can be trusted to be, so every entry is dropped
 * and every read loads, without keeping what it loaded, until the projector runs again. It degrades into a
 * live model, never into a stale one.
 *
 * <p><b>Personal data.</b> An entry holds whatever its state holds. After an erasure, {@link #invalidate}
 * the subject's key (or {@link #invalidateAll()}): the next read loads it and reads the erased values as
 * erased.
 *
 * @param <DOMAIN_EVENT_TYPE> the events of the stream it is projected from
 * @param <KEY>               what an entry is kept by
 * @param <STATE>             the state of one entry: immutable, or guarding its own reads and writes
 */
public abstract class CachingReadModel<DOMAIN_EVENT_TYPE, KEY, STATE>
		implements ReadModel<DOMAIN_EVENT_TYPE>, SelfBookmarkingProjection {

	private static final Logger LOGGER = LoggerFactory.getLogger(CachingReadModel.class);

	private final CacheKeys<KEY> keys;
	private final CachePolicy policy;

	// A lock rather than a monitor: a load that has to catch up with the projector reads from the event
	// store while holding it, and a virtual thread blocking in a monitor pins its carrier on Java 21
	private final ReentrantLock lock = new ReentrantLock();

	// guarded by lock. Insertion ordered, and an entry read is moved to the end, so the eldest is the one
	// read least recently -- moved by hand rather than with access order, so that the projector looking an
	// entry up to fold an event into it does not count as a read
	private final LinkedHashMap<KEY, Entry<STATE>> entries = new LinkedHashMap<>();

	// guarded by lock: how far the projector has got. Every relevant event at or before it has been
	// handed to when(), or never will be; every one after it will be
	private EventReference delivered;

	// whether the projector is not keeping entries current; nothing is held while either is set. Kept
	// apart because they end differently: a failing projector is back once it completes a run, a stopped
	// one only once it is started again -- a run already under way when it was stopped completes after
	// the stop, and must not count as running again
	private volatile boolean failing;
	private volatile boolean stopped;

	private volatile EventSource<DOMAIN_EVENT_TYPE> source;
	private volatile BoundedContextObserver observer = BoundedContextObserver.NOOP;
	private volatile String boundedContext;

	// guarded by lock: the loads under way, per key. A read of a key being loaded waits for that load
	// rather than replaying the key's history a second time
	private final Map<KEY, CompletableFuture<ReadModelResult<STATE>>> loading = new HashMap<>();

	private final AtomicLong hits = new AtomicLong();
	private final AtomicLong misses = new AtomicLong();
	private final AtomicLong evictions = new AtomicLong();
	private final AtomicLong expirations = new AtomicLong();
	private final AtomicLong failedUpdates = new AtomicLong();
	private final AtomicLong sharedLoads = new AtomicLong();
	private final AtomicLong eventsLoaded = new AtomicLong();
	private final AtomicLong eventsCaughtUp = new AtomicLong();
	private final AtomicLong loadMicros = new AtomicLong();

	private static final class Entry<STATE> {
		private STATE state;
		private EventReference upTo;
		private Instant lastRead;

		private Entry ( STATE state, EventReference upTo, Instant lastRead ) {
			this.state = state;
			this.upTo = upTo;
			this.lastRead = lastRead;
		}
	}

	/**
	 * @param keys   what an entry is kept by, usually {@link CacheKeys#byEntity}
	 * @param policy how many entries are kept, and for how long
	 */
	protected CachingReadModel ( CacheKeys<KEY> keys, CachePolicy policy ) {
		if ( keys == null ) {
			throw new IllegalArgumentException("keys must not be null");
		}
		if ( policy == null ) {
			throw new IllegalArgumentException("policy must not be null");
		}
		this.keys = keys;
		this.policy = policy;
	}

	/**
	 * The relevant events of every key: what the projector reads, and what a load narrows to one key with
	 * {@link CacheKeys#narrow}. Read forward from the stream head, so a filter, not a query.
	 */
	@Override
	public abstract EventFilter eventFilter ( );

	/** The state of a key no event is about yet. Must not return null. */
	protected abstract STATE initialState ( KEY key );

	/**
	 * Folds one event into a key's state and returns the next state, leaving the argument untouched (or,
	 * for a state that guards itself, changing it and returning it). The
	 * event is about the key ({@link CacheKeys#keysOf} answered it), and is handed over once per key it is
	 * about. Must not return null.
	 */
	protected abstract STATE apply ( STATE state, Event<DOMAIN_EVENT_TYPE> event );

	/**
	 * The state of {@code key}, from memory when it is held and loaded from the event store otherwise.
	 * A key no event is about answers {@link #initialState}, and is held like any other.
	 */
	public final STATE get ( KEY key ) {
		return result(key).data();
	}

	/**
	 * The state of {@code key} together with the last event folded into it — {@code null} when no event
	 * is about the key yet — so a caller can tell with {@link ReadModelResult#includes} whether its own
	 * write about this key is in it.
	 */
	public final ReadModelResult<STATE> result ( KEY key ) {
		long started = System.nanoTime();
		Answer<STATE> answer = observedRead(key, true);
		return reported(key, started, answer, answer.result(), 0);
	}

	/**
	 * The state of {@code key}, including {@code write}: a held entry that does not include it yet is
	 * loaded again, by a load of this read's own — never one another read had already started, which may
	 * have read the stream before the write. {@code write} must be an event about {@code key}; a write
	 * about another key is never in this one, and costs a load on every call. On PostgreSQL a write still
	 * behind the visibility barrier is not readable yet, by a load or by anything else, and is not in what
	 * this answers.
	 */
	public final STATE getIncluding ( KEY key, EventReference write ) {
		long started = System.nanoTime();
		Answer<STATE> held = observedRead(key, true);
		if ( write == null || held.result().includes(write) ) {
			return reported(key, started, held, held.result(), 0).data();
		}
		invalidate(key);
		Answer<STATE> loaded = observedRead(key, false);
		return reported(key, started, loaded, loaded.result(), 0).data();
	}

	/**
	 * The state of {@code key}, as current as a live read: everything about the key stored when the call
	 * starts is in it. It costs a head lookup, and when the projector has not reached that head yet — it
	 * lags an append by the time a notification takes — it reads the key's events the projector has not
	 * delivered yet into the held entry, holding the projector for that moment. What a caller reaches for
	 * when it may just have written and does not have the reference of its write; with the reference,
	 * {@link #getIncluding} does less.
	 */
	public final STATE getCurrent ( KEY key ) {
		return current(key).data();
	}

	/** {@link #getCurrent}, with the last event folded into the answer. */
	public final ReadModelResult<STATE> current ( KEY key ) {
		long started = System.nanoTime();
		EventSource<DOMAIN_EVENT_TYPE> source = attachedSource();
		EventReference head = source.head().orElse(null);
		Answer<STATE> read = observedRead(key, true);
		if ( head == null ) {
			return reported(key, started, read, read.result(), 0);
		}
		long caughtUp = 0;
		ReadModelResult<STATE> current = null;
		lock.lock();
		try {
			boolean halted = halted();
			Entry<STATE> entry = halted ? null : entries.get(key);
			if ( halted ) {
				// nothing is held: the read loaded the key, from a head taken after this one
				current = read.result();
			} else if ( entry != null ) {
				if ( delivered == null || head.storedEventHappenedAfter(delivered) ) {
					// the projector is behind the head: read what it has not delivered of this key yet. The
					// entry holds every event of the key up to what the projector delivered, and folds
					// nothing at or before the last event in it, so the projector delivering these again
					// later counts for nothing
					CatchUp catchUp = new CatchUp(key, entry);
					Projector.Builder<DOMAIN_EVENT_TYPE> projector = Projector.from(source).into(catchUp);
					if ( delivered != null ) {
						projector = projector.startingAfter(delivered);
					}
					try {
						projector.build().runUntil(head);
					} catch ( RuntimeException e ) {
						entries.remove(key);
						throw e;
					}
					eventsCaughtUp.addAndGet(catchUp.handled);
					caughtUp = catchUp.handled;
				}
				current = new ReadModelResult<>(entry.state, entry.upTo);
			}
		} finally {
			lock.unlock();
		}
		if ( current != null ) {
			return reported(key, started, read, current, caughtUp);
		}
		// let go since it was read, or a read that waited for a load another read started earlier: a load
		// of this read's own reads a head taken after this one
		Answer<STATE> loaded = observedRead(key, false);
		return reported(key, started, loaded, loaded.result(), 0);
	}

	/**
	 * Told of every read, on the reader's thread once the read is answered: what the bounded context
	 * registering a read model sets when its policy reports each read ({@link CachePolicy#reportEachRead()}).
	 */
	@FunctionalInterface
	public interface ReadListener {

		/**
		 * @param key the key read, as its {@code toString()}
		 * @param answer how it was answered
		 * @param eventsCaughtUp the events a current read read in after the hit or the load
		 * @param durationMicros how long the read took, on a monotonic clock
		 */
		void read ( String key, Outcome.CachedReadResult answer, long eventsCaughtUp, long durationMicros );
	}

	private volatile ReadListener readListener;

	/** What the framework calls to be told of every read; {@code null} to be told of none. */
	public final void reportReadsTo ( ReadListener listener ) {
		this.readListener = listener;
	}

	// a read is reported once, whatever path answered it, and a listener that throws never fails the read
	private ReadModelResult<STATE> reported ( KEY key, long started, Answer<STATE> answer, ReadModelResult<STATE> result, long caughtUp ) {
		ReadListener listener = readListener;
		if ( listener != null ) {
			try {
				listener.read(String.valueOf(key), answer.outcome(), caughtUp, ( System.nanoTime() - started ) / 1_000);
			} catch ( RuntimeException e ) {
				LOGGER.warn("{}: reporting a read of key {} failed: {}", readmodelName(), key, e.toString(), e);
			}
		}
		return result;
	}

	private Answer<STATE> observedRead ( KEY key, boolean mayShareALoad ) {
		if ( key == null ) {
			throw new IllegalArgumentException("key must not be null");
		}
		EventSource<DOMAIN_EVENT_TYPE> source = attachedSource();
		try ( Observation.Scope<Outcome.CachedReadResult> scope = observer.start(new Observation.CachedRead(
				boundedContext, readmodelName(), getClass(), String.valueOf(key), TracingScope.current().orElse(null))) ) {
			try {
				Answer<STATE> answer = read(key, source, mayShareALoad);
				scope.completed(answer.outcome());
				return answer;
			} catch ( RuntimeException e ) {
				scope.failed(e);
				throw e;
			}
		}
	}

	private record Answer<STATE> ( ReadModelResult<STATE> result, Outcome.CachedReadResult outcome ) { }

	private Answer<STATE> read ( KEY key, EventSource<DOMAIN_EVENT_TYPE> source, boolean mayShareALoad ) {
		CompletableFuture<ReadModelResult<STATE>> underWay = null;
		CompletableFuture<ReadModelResult<STATE>> ours = null;
		Instant now = policy.clock().instant();
		lock.lock();
		try {
			if ( !halted() ) {
				expire(now);
				Entry<STATE> entry = entries.remove(key);
				if ( entry != null ) {
					entry.lastRead = now;
					entries.put(key, entry);
					hits.incrementAndGet();
					return new Answer<>(new ReadModelResult<>(entry.state, entry.upTo), new Outcome.CacheHit(Optional.ofNullable(entry.upTo)));
				}
			}
			if ( mayShareALoad ) {
				underWay = loading.get(key);
				if ( underWay == null ) {
					ours = new CompletableFuture<>();
					loading.put(key, ours);
				}
			}
		} finally {
			lock.unlock();
		}

		if ( underWay != null ) {
			sharedLoads.incrementAndGet();
			ReadModelResult<STATE> shared = awaitLoad(underWay);
			return new Answer<>(shared, new Outcome.CacheLoadShared(Optional.ofNullable(shared.upTo())));
		}

		misses.incrementAndGet();
		Loaded<STATE> loaded;
		long started = System.nanoTime();
		try {
			loaded = load(key, source);
			loadMicros.addAndGet((System.nanoTime() - started) / 1_000);
			eventsLoaded.addAndGet(loaded.handled());
			eventsCaughtUp.addAndGet(loaded.caughtUp());
		} catch ( RuntimeException | Error e ) {
			loadDone(key, ours);
			if ( ours != null ) {
				ours.completeExceptionally(e);
			}
			throw e;
		}
		loadDone(key, ours);
		if ( ours != null ) {
			ours.complete(loaded.result());
		}
		return new Answer<>(loaded.result(), new Outcome.CacheLoaded(loaded.streamed(), loaded.handled(), loaded.caughtUp(),
				Optional.ofNullable(loaded.result().upTo()), loaded.held()));
	}

	// the waiter gets what the load threw, as itself: the same exception the reader that loaded got
	private static <STATE> ReadModelResult<STATE> awaitLoad ( CompletableFuture<ReadModelResult<STATE>> load ) {
		try {
			return load.join();
		} catch ( CompletionException e ) {
			if ( e.getCause() instanceof RuntimeException runtime ) {
				throw runtime;
			}
			if ( e.getCause() instanceof Error error ) {
				throw error;
			}
			throw e;
		}
	}

	private void loadDone ( KEY key, CompletableFuture<ReadModelResult<STATE>> ours ) {
		if ( ours == null ) {
			return;
		}
		lock.lock();
		try {
			loading.remove(key, ours);
		} finally {
			lock.unlock();
		}
	}

	/** Lets {@code key} go, so its next read loads it from the event store. */
	public final void invalidate ( KEY key ) {
		lock.lock();
		try {
			entries.remove(key);
		} finally {
			lock.unlock();
		}
	}

	/** Lets every key go. */
	public final void invalidateAll ( ) {
		lock.lock();
		try {
			entries.clear();
		} finally {
			lock.unlock();
		}
	}

	/** Whether {@code key} is held, without reading it: a read moves it, this does not. */
	public final boolean holds ( KEY key ) {
		lock.lock();
		try {
			return entries.containsKey(key);
		} finally {
			lock.unlock();
		}
	}

	public final CacheStatistics statistics ( ) {
		int size;
		lock.lock();
		try {
			size = entries.size();
		} finally {
			lock.unlock();
		}
		return new CacheStatistics(size, policy.maxEntries(), hits.get(), misses.get(), eventsLoaded.get(), eventsCaughtUp.get(), loadMicros.get(), evictions.get(),
				expirations.get(), failedUpdates.get(), sharedLoads.get(), halted());
	}

	public final CachePolicy policy ( ) {
		return policy;
	}

	/**
	 * Folds an event the projector delivers into the entries of the keys it is about. A fold that throws
	 * lets that entry go rather than failing the projection: the projector retrying the event would hold
	 * up every other key, and the next read of this one loads it — and fails there, to the caller, if the
	 * fold still does.
	 */
	@Override
	public final void when ( Event<DOMAIN_EVENT_TYPE> event ) {
		EventReference reference = event.reference();
		lock.lock();
		try {
			if ( delivered == null || reference.happenedAfter(delivered) ) {
				delivered = reference;
			}
			if ( entries.isEmpty() ) {
				return;
			}
			for ( KEY key : keys.keysOf(event) ) {
				Entry<STATE> entry = entries.get(key);
				// an entry folds nothing at or before the last event in it: the load read it, or the
				// projector is offering a batch again after a failure
				if ( entry == null || ( entry.upTo != null && !reference.happenedAfter(entry.upTo) ) ) {
					continue;
				}
				try {
					entry.state = applied(entry.state, event);
					entry.upTo = reference;
				} catch ( RuntimeException e ) {
					entries.remove(key);
					failedUpdates.incrementAndGet();
					LOGGER.warn("{}: folding event {} into key {} failed, the key is let go and its next read loads it: {}",
							readmodelName(), reference, key, e.toString(), e);
				}
			}
			if ( policy.expireAfterAccess() != null ) {
				expire(policy.clock().instant());
			}
		} finally {
			lock.unlock();
		}
	}

	private Projector.Builder<DOMAIN_EVENT_TYPE> replay ( EventSource<DOMAIN_EVENT_TYPE> source, Fold fold ) {
		return Projector.from(source).into(fold);
	}

	private record Loaded<STATE> ( ReadModelResult<STATE> result, long streamed, long handled, long caughtUp, boolean held ) { }

	private Loaded<STATE> load ( KEY key, EventSource<DOMAIN_EVENT_TYPE> source ) {
		Fold fold = new Fold(key);

		// the key's history up to the head of this moment, without holding anything up
		EventReference head = source.head().orElse(null);
		ProjectorMetrics replayed = head == null ? ProjectorMetrics.empty() : replay(source, fold).build().runUntil(head);

		if ( halted() ) {
			return new Loaded<>(fold.result(), replayed.eventsStreamed(), replayed.eventsHandled(), 0, false);
		}

		Instant now = policy.clock().instant();
		lock.lock();
		try {
			if ( halted() ) {
				return new Loaded<>(fold.result(), replayed.eventsStreamed(), replayed.eventsHandled(), 0, false);
			}
			Entry<STATE> concurrentlyLoaded = entries.remove(key);
			if ( concurrentlyLoaded != null ) {
				// another load inserted it meanwhile and the projector keeps that entry current, so it stays;
				// this read answers what it replayed itself, which may be further along than what the
				// projector has delivered to that entry yet
				concurrentlyLoaded.lastRead = now;
				entries.put(key, concurrentlyLoaded);
				return new Loaded<>(fold.result(), replayed.eventsStreamed(), replayed.eventsHandled(), 0, true);
			}
			ProjectorMetrics caughtUp = ProjectorMetrics.empty();
			if ( delivered != null && ( head == null || delivered.storedEventHappenedAfter(head) ) ) {
				// The projector got past the head the replay read up to, at a moment this key was not
				// held: the key's events in between were delivered to nobody. Read them now, with the
				// projector held at the lock, so it delivers nothing further before the entry exists. The
				// stretch is what was appended during the replay, usually nothing.
				caughtUp = replay(source, fold).startingAfter(head).build().runUntil(delivered);
			}
			entries.put(key, new Entry<>(fold.state, fold.upTo, now));
			while ( entries.size() > policy.maxEntries() ) {
				Iterator<KEY> eldest = entries.keySet().iterator();
				eldest.next();
				eldest.remove();
				evictions.incrementAndGet();
			}
			return new Loaded<>(fold.result(),
					replayed.eventsStreamed() + caughtUp.eventsStreamed(),
					replayed.eventsHandled() + caughtUp.eventsHandled(),
					caughtUp.eventsHandled(), true);
		} finally {
			lock.unlock();
		}
	}

	// guarded by lock: the eldest entry is the one read least recently, so expiry stops at the first
	// entry read within the idle time
	private void expire ( Instant now ) {
		if ( policy.expireAfterAccess() == null ) {
			return;
		}
		Instant readBefore = now.minus(policy.expireAfterAccess());
		Iterator<Map.Entry<KEY, Entry<STATE>>> eldest = entries.entrySet().iterator();
		while ( eldest.hasNext() ) {
			if ( eldest.next().getValue().lastRead.isAfter(readBefore) ) {
				return;
			}
			eldest.remove();
			expirations.incrementAndGet();
		}
	}

	private STATE applied ( STATE state, Event<DOMAIN_EVENT_TYPE> event ) {
		STATE next = apply(state, event);
		if ( next == null ) {
			throw new IllegalStateException("apply() of " + readmodelName() + " returned null");
		}
		return next;
	}

	private EventSource<DOMAIN_EVENT_TYPE> attachedSource ( ) {
		EventSource<DOMAIN_EVENT_TYPE> attached = source;
		if ( attached == null ) {
			throw new IllegalStateException(("%s is not attached to an event stream: register it on a bounded context with "
					+ "readmodel(instance).eventuallyConsistent(), or attach(...) it in a test").formatted(readmodelName()));
		}
		return attached;
	}

	/** One key's replay. */
	private final class Fold implements Projection<DOMAIN_EVENT_TYPE> {

		private final EventFilter filter;
		private STATE state;
		private EventReference upTo;

		private Fold ( KEY key ) {
			this.filter = keys.narrow(CachingReadModel.this.eventFilter(), key);
			this.state = initialState(key);
			if ( state == null ) {
				throw new IllegalStateException("initialState() of " + readmodelName() + " returned null");
			}
		}

		@Override
		public EventFilter eventFilter ( ) {
			return filter;
		}

		@Override
		public void when ( Event<DOMAIN_EVENT_TYPE> event ) {
			state = applied(state, event);
			upTo = event.reference();
		}

		private ReadModelResult<STATE> result ( ) {
			return new ReadModelResult<>(state, upTo);
		}
	}

	/** A held entry caught up from the stream, under the lock. */
	private final class CatchUp implements Projection<DOMAIN_EVENT_TYPE> {

		private final EventFilter filter;
		private final Entry<STATE> entry;
		private long handled;

		private CatchUp ( KEY key, Entry<STATE> entry ) {
			this.filter = keys.narrow(CachingReadModel.this.eventFilter(), key);
			this.entry = entry;
		}

		@Override
		public EventFilter eventFilter ( ) {
			return filter;
		}

		@Override
		public void when ( Event<DOMAIN_EVENT_TYPE> event ) {
			if ( entry.upTo != null && !event.reference().happenedAfter(entry.upTo) ) {
				return;
			}
			entry.state = applied(entry.state, event);
			entry.upTo = event.reference();
			handled++;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// what the framework calls: a subclass never does

	/**
	 * Hands this read model the stream it is projected from and loads from. Called by the bounded context
	 * that registers it, before its projector is built, and by a test that drives it without one. A read
	 * model is attached to one stream: attaching it to another is refused.
	 */
	public final void attach ( EventSource<DOMAIN_EVENT_TYPE> source ) {
		attach(source, BoundedContextObserver.NOOP, null);
	}

	/**
	 * Hands this read model its stream, and the observer and bounded context its reads are observed
	 * under ({@link Observation.CachedRead}). What the bounded context registering it calls.
	 */
	public final void attach ( EventSource<DOMAIN_EVENT_TYPE> source, BoundedContextObserver observer, String boundedContext ) {
		if ( observer == null ) {
			throw new IllegalArgumentException("observer must not be null");
		}
		attachSource(source);
		this.observer = observer;
		this.boundedContext = boundedContext;
	}

	private void attachSource ( EventSource<DOMAIN_EVENT_TYPE> source ) {
		if ( source == null ) {
			throw new IllegalArgumentException("source must not be null");
		}
		lock.lock();
		try {
			if ( this.source != null && this.source != source ) {
				throw new IllegalStateException(readmodelName() + " is attached to an event stream already: one instance serves one bounded context");
			}
			this.source = source;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Where the projector starts: the head of the stream, since nothing is held that the history before it
	 * could update. Called whenever the projector is (re)built, so every entry is let go here — whatever the
	 * projector delivered before is not what it delivers from now on.
	 */
	@Override
	public final Optional<EventReference> resumeFrom ( ) {
		EventSource<DOMAIN_EVENT_TYPE> source = attachedSource();
		EventReference head = source.head().orElse(null);
		lock.lock();
		try {
			entries.clear();
			delivered = head;
			failing = false;
			stopped = false;
		} finally {
			lock.unlock();
		}
		LOGGER.info("{} holds nothing yet: projecting from the stream head {}", readmodelName(), head);
		return Optional.ofNullable(head);
	}

	/**
	 * Called by the framework when a run of the projector failed and is retried: every entry is let go,
	 * and reads load without holding what they loaded until the projector completes a run again.
	 */
	public final void projectionFailing ( ) {
		halt(true);
	}

	/**
	 * Called by the framework when the projector stopped — retired by a failure, stopped by an operator,
	 * the context stopping: every entry is let go, and reads load without holding what they loaded until
	 * the projector is started again.
	 */
	public final void projectionStopped ( ) {
		halt(false);
	}

	/** Called by the framework after every run the projector completed: a failure is over. */
	public final void projectionRunning ( ) {
		if ( failing ) {
			failing = false;
			resumed();
		}
	}

	/** Called by the framework when the projector is started, at a context start and on a restart. */
	public final void projectionStarted ( ) {
		if ( failing || stopped ) {
			failing = false;
			stopped = false;
			resumed();
		}
	}

	private void halt ( boolean failure ) {
		lock.lock();
		try {
			if ( !halted() ) {
				LOGGER.warn("{}: its projector is {}, so nothing is held and every read loads until it runs again",
						readmodelName(), failure ? "failing" : "stopped");
			}
			if ( failure ) {
				failing = true;
			} else {
				stopped = true;
			}
			entries.clear();
		} finally {
			lock.unlock();
		}
	}

	private void resumed ( ) {
		if ( !halted() ) {
			LOGGER.info("{}: its projector runs again, reads are held again", readmodelName());
		}
	}

	private boolean halted ( ) {
		return failing || stopped;
	}

	/** Always {@link ReadModelStorage#EPHEMERAL}: the entries live in heap, and so does the position. */
	@Override
	public final ReadModelStorage storage ( ) {
		return ReadModelStorage.EPHEMERAL;
	}

	@Override
	public String toString ( ) {
		return readmodelName() + " " + statistics();
	}

}
