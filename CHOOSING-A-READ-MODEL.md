# Choosing a read model

Every read model answers a question out of events. What differs is *when* it is projected, *where* its
state lives, and *what* it starts from — and the framework's registration API is exactly those three
choices.

Each step below buys one thing and costs one thing. **Do not take a step until you have measured the
problem it fixes.** Most read models should stop at step 1.

Every registration has to say which it is — `.live()` or `.eventuallyConsistent()` — and `build()`
rejects one that does not, naming it. The framework could infer it (a class is always live, an instance
always eventually consistent) and deliberately does not: how a read model is projected is the decision
this page is about, and it should be visible where it is made rather than deduced from which overload
was called.

---

## 0. If a command decides on it, it is not a read model

Use a `DecisionModel`, passed to `CommandContext.decisionModels(...)`. That is what puts the events it
was projected from inside the command's consistency boundary, so an append conflicts when one of them
has been superseded.

`CommandContext.read()` exists for auxiliary lookups and is explicitly **outside** the boundary: a
command that decides on what it returns can append against a history the store no longer agrees with,
and nothing will say so. No amount of read-model tuning fixes this, so it is the first question to
settle.

## 1. Start with a live model

```java
public class AccountDetails implements ReadModel<BankingEvent> { ... }

builder.readmodel(AccountDetails.class).live();
AccountDetails details = context.read(AccountDetails.class, accountId);
```

Projected when you read it, from the events its `eventQuery()` matches. Always current, nothing to
rebuild at start-up, no staleness to reason about, no infrastructure.

**Correct whenever the set of events it replays is bounded by design** — scoped by tag to one entity
with a short life, or cut off by a savepoint. If you cannot state that bound in a sentence, it is not
bounded, and step 2 is for you.

*Costs:* one query pass per read, proportional to the matching events.

## 2. Before escalating, bound the replay

Two ways, both cheaper than anything below:

- **Narrow `eventQuery()`** by tags, so a read touches one entity's events rather than the stream.
- **Introduce a savepoint event** and return it from `initQuery()` — a backwards `limit(1)` query for
  the newest summary event, after which only later events are replayed. "Closing the books" in the
  banking example is this: `MonthClosed` summarises a period and `MonthOpened` carries the balance
  forward, so a statement replays one month rather than an account's lifetime.

  Note that `initQuery()` applies to a live model only: a bookmarked projection has to see every event,
  so it is ignored (with a warning at build time) for the eventually consistent read models below.

This is a domain solution and it keeps you at step 1. It is routinely skipped in favour of caching,
which is the more expensive answer to the same problem.

## 3. Eventually consistent, in memory

```java
public class AccountBalances extends PublishingReadModel<BankingEvent, Map<String,Balance>> {
    protected Map<String,Balance> initialState ( )                                     { return Map.of(); }
    protected Map<String,Balance> apply ( Map<String,Balance> s, Event<BankingEvent> e ) { return BalanceFold.apply(s, e); }
    public EventQuery eventQuery ( )                                                    { return EventQuery.matchAll(); }
}

AccountBalances balances = new AccountBalances();
builder.readmodel(balances).eventuallyConsistent();
```

A background processor projects it once and reads become free. Take this when the read spans many
entities — a list, an overview, a total — or when the replay genuinely cannot be bounded.

Extend `PublishingReadModel`: you write a state type, a starting value and a fold, and it publishes the
state together with the position it reflects, atomically, once per batch. That is what makes it safe to
read from another thread, and what lets step 5 build on it.

*Costs:* the answer lags by up to the poll interval; the whole stream is replayed at every process
start (`start()` waits for it, so it is start-up latency, not background).

## 4. Eventually consistent, durable

```java
public class OrdersProjector extends SqlReadModelProjector<OrderEvent> { ... }   // writes rows
public class OrdersQuery     extends SqlReadModelQuery                 { ... }   // reads them
```

Take this when the rebuild at start-up is too slow, the data outgrows heap, or the deployment should
project once rather than once per instance (`ReadModelStorage.SHARED`, projected by a single elected
leader).

*Costs:* tables and migrations; `project()` must be idempotent (`updateOnce`, `insertIfAbsent`,
`insertOnce`); leader election for `SHARED`.

## 5. Seeded read — current, without replaying

```java
public class CurrentBalance implements SeededReadModel<BankingEvent> {

    public Optional<EventReference> seed ( ) {
        ReadModelResult<Map<String,Balance>> base = balances.published();   // one observation
        balance = base.data().getOrDefault(accountId, Balance.ZERO);
        return Optional.ofNullable(base.upTo());
    }

    public void when ( Event<BankingEvent> e ) { balance = BalanceFold.apply(balance, e); }
}

builder.readmodel(CurrentBalance.class).live();
```

Starts from the base built at step 3 or 4 and projects **only the events that have not reached it**.
Take it where the lag from the previous step is unacceptable *for a particular read* — money,
availability, anything a user acts on immediately.

This is a per-read decision, not a per-model one: keep the eventually consistent read for the dashboard
and add a seeded read for the one screen that needs it.

Three rules make it correct:

1. **The state and the position must come from one observation.** Use `PublishingReadModel.published()`
   or `SqlReadModelQuery.loadBaseAt(...)`. Reading them separately straddles whatever the projector
   committed in between — one order re-applies events the base already holds, the other skips events
   that are in neither, and both are silent.
2. **Empty means "replay everything".** An entity with no state yet is *no state loaded* plus
   `Optional.of(position)`, never `Optional.empty()`. Returning empty is still *correct*, so nothing
   fails — it just costs the whole event history on every read.
3. **Write the fold once.** The base and the delta are projected by different code; if they disagree,
   the answer depends on how far the projector happened to get, which is the one thing this exists to
   prevent. Extract it as a pure function of `(state, event)` and call it from both.

*Costs:* a base load plus the delta per read.

## 6. Snapshots — only with no base to seed from

`builder.readmodel(X.class).snapshots(store).readAndWrite()` with `SnapshotCapable`. Reach for it when
there is no projection to seed from and the replay cannot be bounded: a per-entity live model with a
long history and no read model you would otherwise maintain. You implement the `SnapshotStorage`.

---

## Shapes to avoid

**A read model that mutates its own fields and exposes a getter.** The projector thread mutates while
readers call the getter, so a reader can observe a half-applied batch — and iterating a collection
being mutated can throw outright. It also carries no position, so nothing can say how stale an answer
is or catch it up. Extend `PublishingReadModel` instead. (For a model large enough that a copy per
batch is too much, guard a mutable model with a lock and copy out only the answer plus its position.)

**A live model whose replay is unbounded.** Correct in development, linearly slower forever, and
nothing reports it. See step 2.

**Snapshots as a performance patch where a projection exists.** More moving parts than step 5 for the
same effect, and the base is only as fresh as whoever last read past the threshold.

**Catching a read model up by hand in application code.** The fold is the easy part; what goes wrong is
taking the state and the position as two observations. Use `SeededReadModel`.

**Comparing `last_event_position` yourself.** Freshness is the total `(tx, position, index)` order —
`EventReference.happenedAfter` — because a position and a transaction id are assigned independently and
the two orders genuinely disagree. A position-only comparison silently *discards* events. Use
`updateOnce` / `insertOnce` / `loadBaseAt` rather than writing the comparison.

---

## Quick reference

| | projected | state lives in | starts from | answer is |
|---|---|---|---|---|
| Live model | per read | nothing, built per read | the stream | current |
| Live + snapshot | per read | nothing, built per read | a snapshot | current |
| EC in memory (`PublishingReadModel`) | background | heap, per instance | its bookmark | behind by ≤ a poll interval |
| EC durable (`SqlReadModelProjector`) | background | a database | its own bookmark | behind by ≤ a poll interval |
| Seeded (`SeededReadModel`) | per read | its base's | a maintained base | current |

**One caveat that applies to all of them:** "current" means current *as far as the store will show
you*. An event whose transaction is still in flight is withheld from every reader alike — on PostgreSQL
by the `pg_snapshot_xmin` barrier — so a seeded read is exactly as fresh as a live model and no
fresher. What it can prove that a live model cannot is inclusion: expose the reference it reached, and
a caller that has just executed a command checks its own write is in the answer with
`writeRef.happenedBefore(result.upTo())`.
