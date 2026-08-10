# Read models — a developer manual

This manual teaches every way this framework answers a question out of events, in the order you
should learn them — and, more importantly, in the order you should *reach* for them. It is the
long-form companion to [CHOOSING-A-READ-MODEL.md](CHOOSING-A-READ-MODEL.md): that page is the
decision ladder to consult once you know the mechanisms; this one walks the ladder rung by rung,
with the banking examples as the running thread.

The rule that shapes the whole manual comes from that page and is worth repeating before anything
else: **each step buys one thing and costs one thing, and you do not take a step until you have
measured the problem it fixes.** Most read models should stop at chapter 2.

## The five dimensions

Every read model in this framework is a point in the same five-dimensional space. The chapters
introduce the mechanisms one at a time; this matrix is where they all end up.

| Mechanism | Registration | Projected | State lives in | Scope | Starts from | Answer is |
|---|---|---|---|---|---|---|
| Ad-hoc event query (ch. 1) | none | per call | nothing | per call | the query's bounds | current |
| Decision model (ch. 3) | none — passed to `decisionModels(...)` | inline in the command | nothing | per command | the stream (or a savepoint) | current, **inside the consistency boundary** |
| Live model (ch. 2) | `readmodel(X.class).live()` | per read | nothing, built per read | per read | the stream | current |
| Live, bounded (ch. 4) | same | per read | nothing | per read | a tag scope, or the newest savepoint event | current |
| Eventually consistent, in memory (ch. 5) | `readmodel(instance).eventuallyConsistent()` | background | heap, per instance (`EPHEMERAL`) | every instance projects its own copy | its bookmark (rebuilt each start) | behind by ≤ a poll interval |
| Eventually consistent, durable (ch. 6) | `readmodel(instance).eventuallyConsistent()` | background | a database (`EPHEMERAL` / `LOCAL` / `SHARED`) | per instance, or one elected leader for the deployment | its own bookmark, committed with the rows | behind by ≤ a poll interval |
| Seeded read (ch. 7) | `readmodel(X.class).live()` | per read (delta only) | its base's | per read | a maintained base's position | current |
| Snapshot-assisted live (ch. 8) | `readmodel(X.class).snapshots(store)...` | per read (delta only) | nothing (the snapshot store holds the seed) | per read | a stored snapshot | current |

The five axes, spelled out:

- **Projection time** — at read time (current, pay per read), in the background (cheap reads, pay in
  staleness), or inline inside a command (chapter 3, the only one that participates in consistency).
- **Storage** — nothing (rebuilt per read), heap, or a database. A database further splits by *whose*
  it is: `EPHEMERAL` (in-memory, gone with the process), `LOCAL` (durable but private to one
  instance), `SHARED` (one database the whole deployment reads).
- **Scope** — built per individual request, projected per instance, or projected once per deployment
  by a single elected leader.
- **Inspected history** — everything matching, a tag-scoped subset, everything since a savepoint
  event, since a bookmark, since a seeded base, or since a snapshot.
- **Freshness** — *current as of the read* versus *behind by at most the poll interval*. One caveat
  spans all of them: "current" means current as far as the store will show **any** reader. An event
  whose transaction is still in flight is withheld from everyone alike (on PostgreSQL by the
  `pg_snapshot_xmin` barrier), so nothing here is fresher than a live model — but several mechanisms
  can *prove* what they contain, by exposing the reference they reached so a caller checks its own
  write with `writeRef.happenedBefore(result.upTo())`.

---

## 1. Reading events directly — sometimes no read model at all

Before the first read model, know what a bare query can do, because a surprising number of questions
need nothing more. An `EventStream` answers an `EventQuery` — event types, tags, direction, limit:

```java
// the newest fact of one kind about one entity: one query, one event
Optional<Event<BankingEvent>> lastClose = eventStream
    .query(EventQuery.forEvents(EventTypesFilter.of(MonthClosed.class),
                                DomainConceptTags.of(CONCEPT_ACCOUNT, accountId))
                     .backwards().limit(1))
    .findFirst();
```

"Has this happened yet", "what was the last X", "give me this entity's events for the audit screen" —
all of these are a query, not a projection. Two things to know before leaning on it:

- **The returned `Stream` is already fully in memory.** Storage has finished reading by the time it
  comes back, so `.findFirst()` or `.limit(10)` on the *stream* discards work already done — put the
  bound in the *query* (`.limit(n)`), where it becomes a storage-level limit. An unbounded query over
  a large stream is a heap problem, not a slow stream.
- **`.limit(n)` counts stored events.** With upcasting in play one stored event can become several or
  none, so bound the query by stored events and trim the returned stream if you need exactly n.

When the question stops being "which events" and starts being "what do they add up to", you fold —
and a fold with a name, a query and a place to be registered is a read model. That is chapter 2.

## 2. Your first read model: the live model

A live model is a class that says which events it wants and what each one does to its state. It is
projected **when you read it**: the framework instantiates it with your parameters, replays the
matching events through it, and hands it back. No background thread, no staleness, nothing to rebuild
at startup, nothing to migrate.

The whole of [`AccountDetailsReadModel`](sliceworkz-eventmodeling-examples/src/main/java/org/sliceworkz/eventmodeling/examples/banking/features/accountdetails/AccountDetailsReadModel.java), abbreviated:

```java
public class AccountDetailsReadModel implements ReadModel<BankingDomainEvent> {

    private final DomainConceptId accountId;
    private AccountDetails account;

    public AccountDetailsReadModel ( DomainConceptId accountId ) {   // matches the read's parameters
        this.accountId = accountId;
    }

    @Override
    public EventQuery eventQuery ( ) {
        return EventQuery.forEvents(EventTypesFilter.any(),
                DomainConceptTags.of(BankingDomain.CONCEPT_ACCOUNT, accountId));
    }

    @Override
    public void when ( BankingDomainEvent event ) {
        switch ( event ) {
            case AccountOpened ao -> this.account = AccountDetails.of(...);
            default -> { }
        }
    }
}
```

Registered and read:

```java
builder.readmodel(AccountDetailsReadModel.class).live();
AccountDetailsReadModel details = bc.read(AccountDetailsReadModel.class, accountId);
```

Three things to internalise here, because everything later builds on them:

- **The registration says the mode out loud.** `build()` rejects a `readmodel(...)` registration that
  never said `.live()` or `.eventuallyConsistent()`, naming every offender. The verb is technically
  redundant (a class can only be live, an instance only eventually consistent) and that is the point:
  how a read model is projected is a decision its callers live with, so it is stated where it is
  made. (`ReadModelModeIsExplicitTest` pins this.)
- **Constructor parameters are the read's parameters.** `bc.read(X.class, a, b)` picks the
  constructor with a matching parameter count. This is what scopes the model to one entity — and
  what scopes its `eventQuery()`.
- **`ReadModel` hides event metadata; `ReadModelWithMetaData` exposes it.** Start with `ReadModel`
  (`when(D event)` — just the data); switch to the metadata variant when you need tags, timestamps or
  the event reference.

**The one rule of live models: the replay must be bounded by design.** A live model scoped by tag to
one entity with a short life is perfect. One that replays an unbounded, ever-growing set is correct
in development, linearly slower forever in production, and nothing reports it. The test is blunt: if
you cannot state the bound in one sentence ("an account's period holds at most a month of
transactions"), it is not bounded — go to chapter 4 *before* considering anything heavier.

## 3. Reads a command decides on are not reads

Before scaling reads up, one boundary must be drawn, because getting it wrong is silent and
unfixable by any read-model tuning: **state a command bases its decision on is not a read model — it
is a `DecisionModel`.**

A `DecisionModel` looks exactly like a projection (`eventQuery()`, optionally `initQuery()`,
`when(...)`) but is not registered anywhere. The command constructs it and passes it to
`CommandContext.decisionModels(...)`, which projects it inline during `execute()` — and, crucially,
makes the events it was projected from the command's **consistency boundary**: if a concurrent
append supersedes one of them before this command's events land, the append fails with an
optimistic-locking conflict instead of writing against a history the store no longer agrees with.

`CommandContext.read(...)` also exists inside a command, and it is explicitly **outside** that
boundary — it is for auxiliary lookups only. A command that *decides* on what `read()` returned can
append against stale history and nothing will ever say so.

The in-tree example is [`ActivePeriodDecisionModel`](sliceworkz-eventmodeling-examples/src/main/java/org/sliceworkz/eventmodeling/examples/banking/features/currentperiod/ActivePeriodDecisionModel.java):
the deposit, withdraw and close-month commands all project it via `context.decisionModels(...)`, and
its `eventQuery()` doubles as the conflict filter. Note that it uses the same savepoint pattern
chapter 4 teaches — decision models and read models share the whole projection vocabulary; they
differ only in *who* runs them and what the projection *guards*.

Teaching rule of thumb: **read models are for screens and integrations; decision models are for
commands.** Settle that split first — it is rung 0 of the ladder for a reason.

## 4. Keeping live models cheap: bound the replay

When a live model gets slow, the reflex is to cache it. The framework's position is that this is the
expensive answer to a problem the *domain* usually solves more cheaply. Two techniques, both of which
keep you at chapter 2's cost model:

**a) Narrow `eventQuery()` by tags.** Tags are the query dimension of this event store; a model that
filters by entity tag replays one entity's events, not the stream.
[`MonthStatementReadModel`](sliceworkz-eventmodeling-examples/src/main/java/org/sliceworkz/eventmodeling/examples/banking/features/monthstatement/MonthStatementReadModel.java)
goes one further and filters by account **and** month, so one statement replays one period — and for
a closed month that set is immutable, which makes the answer trivially cacheable if you ever want to.
The design work is in the *events*: the closing-the-books example tags each period's events with the
month, rotating the tag when the books close, precisely so this query stays small.

**b) Introduce a savepoint event, and return it from `initQuery()`.** A savepoint is an ordinary
domain event that *summarises* everything before it — `MonthClosed` summarises a period,
`MonthOpened` carries the closing balance forward. A live model finds the newest one with a
backwards `limit(1)` query and replays only what came after:

```java
// CurrentPeriodReadModel — the savepoint pattern
@Override
public EventQuery initQuery ( ) {
    return EventQuery.forEvents(
        EventTypesFilter.of(AccountOpened.class, MonthOpened.class),   // the savepoints
        DomainConceptTags.of(CONCEPT_ACCOUNT, accountId)
    ).backwards().limit(1);
}

@Override
public EventQuery eventQuery ( ) {
    return EventQuery.forEvents(
        EventTypesFilter.of(MoneyDeposited.class, MoneyWithdrawn.class, MonthClosed.class),
        DomainConceptTags.of(CONCEPT_ACCOUNT, accountId)               // movements only
    );
}
```

The framework runs `initQuery()` first, feeds its result through `when(...)`, then starts
`eventQuery()` from that event's position. With no savepoint yet, the init query finds nothing and
the model gracefully replays from the beginning. Rules that keep it healthy:

- **Query disjoint event types from the two methods.** The savepoint types belong to `initQuery()`
  only — otherwise they are double-processed, and a buggy savepoint cannot be recovered from by
  ignoring it.
- **`initQuery()` is a live-model device.** A bookmarked background projection must see every event,
  so eventually consistent read models ignore it (with a build-time warning).
- The savepoint is written by the domain — a `CloseMonthCommand` deciding the books are closed — not
  by the framework. That is a feature: "closing the books" is a business fact with meaning of its
  own, and the read-model speedup falls out of it.

**When to leave chapter 4:** the replay genuinely cannot be bounded — the read spans *many* entities
(a list, an overview, a total), or no savepoint makes domain sense. That, and only that, justifies a
background projection.

## 5. Going eventually consistent, in memory

An eventually consistent read model is projected **once, in the background**, by its own processor.
Reads become free; the price is that the answer lags whatever the projector has not reached yet.
You register an **instance** rather than a class — one long-lived object, fed continuously:

```java
AccountBalancesReadModel balances = new AccountBalancesReadModel();
builder.readmodel(balances).eventuallyConsistent();
```

Do not hand-roll the obvious shape — a class that mutates a `Map` field from `when()` and exposes a
getter. The projector thread mutates while readers read, so a reader can observe a half-applied
batch (or an outright `ConcurrentModificationException`), and the answer carries no position, so
nothing can say how stale it is. The framework's template is **`PublishingReadModel`**, and the
subclass writes exactly three things — a state type with an initial value, a pure fold, and a query:

```java
public class AccountBalancesReadModel
        extends PublishingReadModel<BankingEvent, Map<DomainConceptId,BigDecimal>> {

    @Override
    protected Map<DomainConceptId,BigDecimal> initialState ( ) {
        return Map.of();
    }

    @Override
    protected Map<DomainConceptId,BigDecimal> apply ( Map<DomainConceptId,BigDecimal> state,
                                                      Event<BankingEvent> event ) {
        DomainConceptId account = BalanceFold.accountOf(event.data());
        Map<DomainConceptId,BigDecimal> next = new HashMap<>(state);
        next.put(account, BalanceFold.apply(state.getOrDefault(account, BigDecimal.ZERO), event.data()));
        return Map.copyOf(next);
    }

    @Override
    public EventQuery eventQuery ( ) {
        return EventQuery.matchAll();
    }
}
```

What the base class does with that: it folds each batch aside and publishes **the immutable state
together with the position it reflects**, through one volatile write, once per batch. A reader calls
`published()` and gets both as **one observation** — a `ReadModelResult<STATE>` whose `upTo()` is
exactly the last event folded in. That one property is what makes cross-thread reads safe, what lets
a caller answer "is my write in this yet" (`writeRef.happenedBefore(upTo())`), and what chapter 7
builds on. The contract in return: **never mutate state after `apply` returns it.**

Operational facts to teach alongside:

- **Storage is `EPHEMERAL`**: heap, gone with the process, every instance projects its own copy.
  The state is rebuilt from the beginning at every start — and `bc.start()` **waits** for every
  ephemeral read model to finish its initial projection (safety timeout 5 minutes,
  `-Dsliceworkz.eventmodeling.readmodel.ephemeral.projection.timeout.ms=...`), so this is startup
  latency, not background noise. When that rebuild gets slow, chapter 6 is the answer.
- **Staleness is bounded and observable**, not vague: at most the poll interval behind, and every
  answer can carry its `upTo`.
- **The read model's name is its bookmark key.** It defaults to the class' simple name; override
  `readmodelName()` to run one class as several differently-named instances. Names must be unique
  and stable — two components sharing a name share a bookmark and silently skip each other's events.
- **A projector's first failure is its last.** One throwable out of a projection stops that
  processor until the context is started again; `ReadModelProjectorStarted` / `Stopped` events are
  how the outside world tells "idle" from "dead" (see chapter 9).

## 6. Durable, shared, and elected: the SQL read model

Take this step when the startup rebuild is too slow, the state outgrows heap, or the deployment
should project **once** rather than once per instance. The state moves into a database, and with it
come the three questions this chapter answers: who owns the tables, who is allowed to project, and
how progress survives a crash.

The mechanism is a pair — a projector that writes rows and a query class that reads them — both
registered/used like any other code, the projector as an eventually consistent instance:

```java
public class OrderCountProjector extends SqlReadModelProjector<Order> {

    public OrderCountProjector ( DataSource dataSource ) {
        super(dataSource, "rm_orders");                        // table prefix
    }

    @Override
    protected String[] createTables ( ) {
        return new String[] { """
                CREATE TABLE IF NOT EXISTS %s (
                    customer_id VARCHAR(255) NOT NULL PRIMARY KEY,
                    total_order_count INT NOT NULL,
                    last_order_date VARCHAR(32),
                    %s
                )""".formatted(table("customers"), EVENT_REF_COLUMNS) };
    }

    @Override
    public EventQuery eventQuery ( ) {
        return EventQuery.matchAll();
    }

    @Override
    protected void project ( Event<Order> event ) {
        Order order = event.data();
        insertIfAbsent(table("customers"), "customer_id = ?", new Object[] { order.customerId() },
                "customer_id, total_order_count", order.customerId(), 0);
        updateOnce(table("customers"),
                "total_order_count = total_order_count + 1, last_order_date = ?",
                new Object[] { order.orderedAt() },
                "customer_id = ?", order.customerId());
    }
}
```

**Progress lives next to the state — that is the whole design.** A read model writing rows in its
own database and a bookmark living in the event store are two stores with no transaction across
them; a crash between the two turns into duplicated rows and double-counted totals, permanently and
silently. `SqlReadModelProjector` therefore implements `SelfBookmarkingProjection`: every batch
writes its last event's reference into a `<prefix>_projection_bookmark` table **inside the
transaction that writes the rows**, and startup resumes from that table. The event-store bookmark is
still written — as the record operators and dashboards see, lagging by at most one batch — but never
read. An absent bookmark row means "replay from the beginning", deliberately with no fallback: that
is what makes *drop the tables* the complete rebuild procedure.

**`project()` must be idempotent, and the helpers are how.** Leader election (below) keeps two
instances from projecting concurrently, but a failover window is at-least-once, so a batch can be
repeated. Three helpers cover the shapes:

- `updateOnce(...)` — an UPDATE applied at most once per event per row; the `EVENT_REF_COLUMNS` on a
  row mean "the newest event this row reflects". This is the ordinary aggregate.
- `insertIfAbsent(...)` — creates the row `updateOnce` guards, and deliberately leaves the freshness
  columns at their zero defaults. (Writing the current event's reference there is the
  natural-looking mistake: the `updateOnce` that follows would skip, and the first order of every new
  customer would never be counted.)
- `insertOnce(...)` — for append-style tables (a log, a dead-letter view), keyed on the event id.

Do not hand-roll the freshness comparison the helpers encapsulate: it is the total
`(tx, position, index)` order, never `last_event_position` alone — position and transaction id are
assigned independently and genuinely disagree, and a position-only guard silently *discards* events.

**Where the state lives decides who projects — `ReadModelStorage`:**

| | state | projected by | bookmark at start |
|---|---|---|---|
| `EPHEMERAL` | in-memory, gone with the process | every instance, own copy | dropped — full replay |
| `LOCAL` | durable, private to one instance | every instance, own copy | own, resumed |
| `SHARED` | one database for the deployment | **a single elected leader** | shared, resumed |

`SqlReadModelProjector` detects it from the DataSource — in-memory H2 ⇒ `EPHEMERAL`, anything else ⇒
`SHARED` — and you override `storage()` for `LOCAL` (a durable database that is nonetheless private
to one instance). The deliberate asymmetry: mistaking shared for ephemeral would drop the bookmark
and duplicate rows on every start, so `SHARED` is the fallback.

**`SHARED` means leader election.** One lease per processor, held in the event storage; the elected
leader projects, everyone else stands by and answers queries from the same tables. Configure with
`.leadershipPriority(long)` and `.leadershipIntervals(heartbeat, ttl)` on the builder. What election
does *not* promise is exactly-once through a failover — a leader paused past its ttl can commit one
last batch. The fencing token closes half of that window (a superseded leader's commit fails whole
with `StaleLeadershipException`, rolled back rows and all); the idempotent helpers close the rest.
This is why the helpers are not optional politeness.

Reading happens through `SqlReadModelQuery` — plain `queryList`/`querySingle`, or
`queryListWithRef`/`querySingleWithRef` returning a `ReadModelResult` whose `upTo` is the newest
event any *returned row* reflects. That per-row reference answers "is my write in this row"; it is a
lower bound on the projection as a whole and **not** a base to seed from — chapter 7 uses
`loadBaseAt(...)` instead, for exactly that reason.

**Writing a projector for a non-SQL store.** Nothing ships, but the contract is small:
implement `SelfBookmarkingProjection` (+ the eventstore's `BatchAwareProjection`) against your
store. The requirement that decides feasibility is atomicity of state and position — if your store
cannot commit the rows and the bookmark together, you get at-least-once and everything above about
idempotency applies doubly. `SqlReadModelProjector` is the worked example to copy;
`SqlReadModelSurvivesRestartTest` is the test to imitate (project, remove the framework bookmark,
restart — the row count must not double).

## 7. Current again, without the replay: the seeded read

Chapters 5–6 bought cheap reads and paid in staleness; chapter 2 was current and paid per read.
The seeded read is the combination, for the reads where the lag is unacceptable — money,
availability, anything a user acts on immediately: it loads a **base** from an eventually
consistent model and projects **only the events that have not reached it**, usually none.

It is a live model with one extra method, and it is a *per-read* decision — the dashboard keeps
reading the eventually consistent model; the one screen that decides adds a seeded read next to it:

```java
public class CurrentBalanceReadModel implements SeededReadModel<BankingEvent> {

    private final AccountBalancesReadModel balances;    // the chapter-5 base
    private final DomainConceptId accountId;
    private BigDecimal balance = BigDecimal.ZERO;
    private EventReference upTo;

    @Override
    public Optional<EventReference> seed ( ) {
        // rule 1: state and position in ONE observation
        ReadModelResult<Map<DomainConceptId,BigDecimal>> published = balances.published();
        // rule 2: an unknown account is a zero balance AT that position, never an absent base
        balance = published.data().getOrDefault(accountId, BigDecimal.ZERO);
        upTo = published.upTo();
        return Optional.ofNullable(upTo);
    }

    @Override
    public EventQuery eventQuery ( ) {          // only this account -> the delta stays tiny
        return EventQuery.forEvents(EventTypesFilter.any(),
                Tags.of(DomainConceptTag.of(CONCEPT_ACCOUNT, accountId)));
    }

    @Override
    public void when ( Event<BankingEvent> event ) {
        balance = BalanceFold.apply(balance, event.data());   // rule 3: the SAME fold as the base
        upTo = event.reference();
    }
}

builder.readmodel(CurrentBalanceReadModel.class).live();
bc.read(CurrentBalanceReadModel.class, balances, accountId);
```

The three rules in that snippet are the entire correctness story, and each fails *silently* when
broken:

1. **One observation.** State and position taken separately straddle whatever the projector
   committed in between — one order re-applies events the base already holds, the other loses events
   into neither base nor delta. The two supported observations are `PublishingReadModel.published()`
   (in-memory) and `SqlReadModelQuery.loadBaseAt(reader, loader)` (SQL — it reads the bookmark on
   both sides of the load and retries while they differ, which is sound because the projector
   commits rows and bookmark in one transaction; your loader may run more than once and must
   *replace* what it loaded).
2. **Empty means "replay everything".** An entity the base has never seen is *default state at the
   base's position*, never `Optional.empty()`. Returning empty is still correct — no test goes red —
   it just replays the entire history on every read, and only hurts once the stream is long. The
   `LiveModelProjected` event carries `seededAt` for exactly this: a seed quietly returning empty is
   otherwise invisible.
3. **Write the fold once.** Base and delta are projected by different code paths; extract the rule
   as a pure `(state, event)` function ([`BalanceFold`](sliceworkz-eventmodeling-examples/src/main/java/org/sliceworkz/eventmodeling/examples/banking/features/currentbalance/BalanceFold.java))
   and call it from both, or the answer starts depending on how far the projector got — the one
   thing this mechanism exists to prevent.

Two misregistrations are rejected at build time, both silent failures otherwise: a `SeededReadModel`
registered `.eventuallyConsistent()` (its processor resumes from a bookmark, so `seed()` would never
run) and one registered with `.snapshots(...)` (two complete answers to "where does this projection
start"). And remember the universal caveat: seeding buys the *cost* of a live model down, not the
visibility rules away — it is exactly as fresh as a live model, and what it adds is proof, via
`upTo()`.

`SeededReadModelTest` pins the property that matters — the answer does not depend on how far the
projector got — and counts the delta, because a correct-but-ignored seed is otherwise invisible.

## 8. Snapshots — the last resort

One gap remains: a **per-entity** live model with a long history, no savepoint that makes domain
sense, and no maintained projection to seed from. For that, and only that, the framework snapshots
the live model itself:

```java
builder.readmodel(AccountHistoryReadModel.class)
       .snapshots(snapshotStorage)          // implies .live()
       .eventCountThreshold(1000)           // write a snapshot after a read this expensive
       .readAndWrite();                     // or readOnly() / writeOnly()
```

The read model implements `SnapshotCapable<S>` — `takeSnapshot()`, `fromSnapshot(S)`, and a
`version()` string that must match exactly for a snapshot to be loaded (bump it when the state shape
changes; stale-version snapshots are simply ignored and rewritten). You implement `SnapshotStorage`
yourself — `load(key, version)` and `save(key, version, snapshot, eventReference)` — against
whatever store suits (a table, a document store, a blob bucket). A read then loads the snapshot,
projects only the events after its reference, and writes a fresh snapshot when the read streamed
more than the threshold.

Why last: it is more moving parts than a seeded read for the same effect, and the base is only as
fresh as whoever last read past the threshold. If a projection exists that could seed the read, seed
it (chapter 7). If a savepoint could bound the replay, bound it (chapter 4). Snapshots are for when
neither is true.

## 9. Reference

### When to take the next step

| You observe | Take |
|---|---|
| The question is "which events", not "what do they fold to" | ch. 1 — a query |
| A command needs state to decide on | ch. 3 — a decision model, always |
| A live read is measurably slow | ch. 4 **first** — bound the replay |
| The read spans many entities, or no bound exists | ch. 5 — eventually consistent, in memory |
| Startup rebuild too slow, state outgrows heap, or project-once-per-deployment | ch. 6 — durable SQL |
| The eventually consistent answer is not current enough *for one read* | ch. 7 — seed that read |
| Per-entity, long history, no savepoint, no base | ch. 8 — snapshots |

The shapes to steer away from — the mutable-getter read model, the unbounded live model, snapshots
as a performance patch, hand-rolled catch-up, hand-rolled position comparison — are catalogued with
their failure modes in [CHOOSING-A-READ-MODEL.md](CHOOSING-A-READ-MODEL.md#shapes-to-avoid).

### Special forms

- **`TodoListReadModel`** — an eventually consistent read model that serves as an automation's work
  queue. Same registration, two extra obligations: `streamItems` owns ordering, and it must not be a
  live view its own projector can mutate mid-batch.
- **`readUnbounded(...)`** (`UnboundedReadModelCapability`) — a live read across *all* event streams,
  ignoring context boundaries. Internal dashboards and monitoring only; a context opts in by
  extending the capability interface.

### Observability

- `LiveModelProjected` — per live read: metrics plus `seededAt` (how a silently-empty seed shows up).
- `ReadModelProjectorStarted` / `ReadModelProjectorStopped` — fold the pair, later one wins, to
  answer "is it still projecting". `Stopped` carries the cause and the event it died on; it is not
  emitted at shutdown, so it always means the one state worth alerting on. There is no
  `...Failed` event: a projector's first failure is its last.
- `EventuallyConsistentReadModelUpdated` — only for catch-ups that handled something.
- For `SHARED`: `LeadershipAcquired` / `LeadershipReleased` report the election axis separately.

### Testing

- `LiveModelTest` (published in `sliceworkz-eventmodeling-testing`) — `given().events(...)`
  `.when(params)` `.then().liveModelIs(...)` for chapters 2, 4, 7 and 8.
- `SqlReadModelTest` — H2 and PostgreSQL DataSources, plus `projectedUpTo()` and
  `restartedProjector()` so you can assert the chapter-6 restart property yourself.
- Annotate scenarios `@ForEachBackend` to run them against every registered event storage backend; a
  plain `@Test` runs once in-memory. Storage-specific behaviour — DCB conflicts under a real
  advisory lock, ordering where position and transaction disagree — only shows up on the real
  backends.
