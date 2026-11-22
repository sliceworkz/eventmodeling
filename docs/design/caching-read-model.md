# Caching read model: holding only the keys being read

`CachingReadModel` is an in-memory read model that holds a bounded number of keys. A key is loaded
from the event store on its first read, kept current by a projector from then on, and evicted, least
recently read first, when the `CachePolicy` needs the room. The rules a change must respect are in the
caching section of `CLAUDE.md`; this file holds why the design is what it is.

## The problem

Two read models are on offer for "the state of one customer":

- **A live model** replays the customer's events on every read. It holds nothing, so memory is never
  the problem, and it is always current. Its cost is the replay, every time.
- **An eventually consistent in-memory read model** (`PublishingReadModel`) projects every customer once
  and answers from memory. Its cost is memory proportional to the number of customers, and a replay of
  the whole stream at every start.

With ten million customers and a few thousand being worked with on a given morning, neither fits: the
first holds nothing it could reuse, the second holds everything. A durable SQL read model (with a
`SeededReadModel` over it for current reads) does fit, at the price of a database, tables and
migrations. The caching read model sits between: memory bounded by the policy, no infrastructure, and a
key's replay paid once while it is in use instead of on every read.

## Is it worth it?

Only where reads repeat. The hit rate is the whole case: a miss costs exactly a live read of the key,
and the projector beside the cache reads every relevant event appended whether or not a held key is
about it. Reads spread evenly over far more keys than the cache holds miss almost every time, and the
read model is then a live model plus a projector, strictly worse than the live model alone.
`statistics()` reports hits, misses, evictions and expirations so a deployment can tell.

The miss is cheap on PostgreSQL, where a key's events are found through the tag index. In memory it is
a scan of the whole log, as every tag query there is; the in-memory backends are for correctness, not
for sizing.

## Correctness: the load and the projector

The projector delivers every relevant event to `when`, which folds it into the entries of the keys it is
about and drops it otherwise. The only hard part is a key loaded while the projector runs.

A miss replays the key's events up to the stream head `h` it read first. Meanwhile the projector has got
to some point `d` (`delivered`: every relevant event at or before it was handed to `when`, or never will
be). Two cases, decided under the lock that `when` also takes:

- **`d` at or before `h`.** The events between `d` and `h` are in the replay and will also be delivered.
  The entry records the last event folded into it and folds nothing at or before it, so the delivery
  counts for nothing. Everything after `h` is delivered and folded. The entry is inserted as it is.
- **`d` after `h`.** The projector delivered the events between `h` and `d` while the key was not held,
  so they were dropped. The load replays the key after `h` up to `d`, still holding the lock, so the
  projector cannot deliver anything further before the entry exists. That stretch is what was appended
  during the replay: usually nothing, a handful of events under load.

The "folds nothing at or before the last event in it" rule uses the total `(tx, position, index)` order
(`happenedAfter`). The replay bounds are stored-event bounds (`runUntil`, `startingAfter`), which read a
stored event whole, so the last event a replay folds is the highest-indexed one of its stored event that
is about the key. That is what lets one comparison serve the replay, the projector, and a batch the
projector re-offers after a failure.

The test of this rule drives the read model by hand over a stream whose `head()` answers an older
reference, which puts the load in the second case deterministically. A test through a running context
would hit it only by chance.

## Starting at the head

At start, and whenever the projector is rebuilt, nothing is held, so the history could update nothing.
The read model is a `SelfBookmarkingProjection` whose `resumeFrom()` answers the stream head and lets
every entry go. A start therefore reads no history, where an `EPHEMERAL` read model normally replays
the stream (and `start()` waits for it). The cost is that nothing is warm after a start.

## When the projector is not running

Held entries are only as current as the projector. A projector that fails and retries, is retired, is
stopped by an operator, or stops with its context keeps nothing current, and a hit would then answer
stale data for as long as that lasts (minutes, with the projector's backoff). So the read model lets
every entry go and loads every read without holding what it loaded, until the projector runs again: it
degrades into a live model.

"Failing" and "stopped" are separate flags because they end differently. A failure ends with the next
completed run. A stop ends only when the projector is started again: a run already under way when an
operator stops the projector completes after the stop, and its completion must not count as the
projector running.

A fold that throws in the projector lets that key go rather than failing the run. Failing the run would
make the projector retry the event and hold up every other key; letting it go moves the failure to the
key's next read, where its caller sees it.

## Rejected alternatives

- **A setting on an existing eventually consistent read model** (`.eventuallyConsistent().cached(5000)`).
  An arbitrary read model's state is one object, not a map of keys; the framework cannot evict part of
  it, and cannot load one key into it.
- **Caching live model instances** (`.live().cached(5000)`, keyed by the constructor parameters). It
  would reuse every existing live model unchanged, which is attractive. It loses because a live model
  instance belongs to its caller: it is mutable, built for one read, and read without any lock. Kept
  and updated by a projector thread, it would be mutated while callers read it. It also offers no way to
  route an event to the instances it is about short of matching every held instance's filter against
  every event.
- **Holding the lock for the whole load.** Simpler, and correct, but every miss would stop the
  projector for the length of a replay, and every other read with it. Only the catch-up, which is
  usually empty, runs under the lock.
- **Replaying the history at start** like any `EPHEMERAL` read model. Pointless with nothing held, and
  for the data sets this exists for it is the full-stream read the design is meant to avoid.
- **A monitor instead of a `ReentrantLock`.** The catch-up reads from the store while holding the lock,
  and a virtual thread blocking inside a monitor pins its carrier on Java 21.

## Two reads of one key

A read that finds its key not held, while another read is loading it, waits for that load and answers
what it loaded (`loading`, one `CompletableFuture` per key under the same lock). Without it a key read by
twenty requests at once after a restart would be replayed twenty times. What the load threw reaches
every read that waited for it, and the future is gone once the load ends, so the next read loads afresh.

`getIncluding` is the exception: when the held or shared answer does not include the caller's write, it
loads the key itself rather than joining a load under way, which may have read the stream before the
write.

## Observed like a live read

Every read is an `Observation.CachedRead` on the caller's thread, answering `CacheHit`, `CacheLoaded`
(events read, events folded, how many of them the catch-up read, whether the key is held now) or
`CacheLoadShared`. The event store's queries of a load nest beneath it. The key travels as its
`toString()`, which is data: a metrics binding must bound it. The alternative — reporting a load as a
`LiveModelRead` — loses because a hit is an answer worth counting too, and the hit rate is the number
that says whether the read model is worth having.

## Testing

`CachingReadModelTest` in the testing module, beside the other bases. It does not register the read
model, for the reason `AutomationTest` does not register an automation: a registered one projects on a
thread of its own, racing the test. It attaches the read model to the context's domain stream, starts it
with `resumeFrom()` as a deployment does, and projects it with an eventstore `Projector` when the test
says `projected()`. So the lag between an append and a held key seeing it is a step of the test. The
read model is created before the given events, so a load reads them and the projector then delivers them
again, and every test exercises the rule that counts an event once.
