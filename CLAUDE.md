# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based Event Modeling framework implementing event-sourcing patterns with a focus on vertical slice architecture. The project is organized as a Maven multi-module build using Java 21.

**Core Modules:**
- `sliceworkz-eventmodeling-api`: Public API defining interfaces and abstractions
- `sliceworkz-eventmodeling-impl`: Framework implementation using ServiceLoader pattern
- `sliceworkz-eventmodeling-testing`: Base classes and utilities for testing
- `sliceworkz-eventmodeling-tests`: Integration tests, run against every event storage backend (in-memory, in-memory-fs, PostgreSQL 17/18)
- `sliceworkz-eventmodeling-examples`: Example applications (banking domain)
- `sliceworkz-eventmodeling-bom`: Bill of Materials for dependency management
- `sliceworkz-eventmodeling-parent-pom`: Parent POM with shared configuration

**External Dependencies:**
- Uses `org.sliceworkz:sliceworkz-eventstore` library (version in `sliceworkz.eventstore.version`, root pom) for event storage abstraction
- EventStore provides PostgreSQL, in-memory and file-backed in-memory implementations
- `sliceworkz-eventstore-testing` supplies the backend harness this project's tests run on (see Testing Approach)

## Build Commands

**Build entire project:**
```bash
mvn clean install
```

**Build specific module:**
```bash
cd sliceworkz-eventmodeling-api
mvn clean install
```

**Run all tests:**
```bash
mvn test
```

**Skip tests during build:**
```bash
mvn clean install -DskipTests
```

**Run specific test class:**
```bash
mvn test -Dtest=BankingExampleTest
```

**Check for dependency updates:**
```bash
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates
```

**Verify license headers:**
```bash
mvn license:check
```

## Running Example Applications

**Banking Example (main method):**
```bash
cd sliceworkz-eventmodeling-examples
mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.banking.BankingExample"
```

The example demonstrates:
- Creating a bounded context with in-memory event storage
- Executing commands (OpenAccountCommand)
- Querying read models (AccountDetailsReadModel, AccountOverviewReadModel)
- Event stream subscriptions

**Closing The Books Example — bounded periods, and a read that is current without replaying (main method):**
```bash
cd sliceworkz-eventmodeling-examples
mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.banking.BankingClosingTheBooksExample"
```

Two ways of keeping a read cheap on a long history, side by side. The domain one is "closing the books":
`MonthClosed` summarises a period and `MonthOpened` carries the balance forward, so `MonthStatementReadModel`
replays one month rather than one account's lifetime. The framework one is step 3b — `CurrentBalanceReadModel`,
the file to read when a live model is impractical but an eventually consistent read is not current enough to
decide on. It seeds from `AccountBalancesReadModel` (a `PublishingReadModel` projected in the background) and
projects only what has not reached it, usually nothing. Note that both share one `BalanceFold`: the base and
the delta are folded by different code paths, and the moment they disagree the answer starts depending on how
far the projector got.

**Payments Example — an automation whose work can fail (main method):**
```bash
cd sliceworkz-eventmodeling-examples
mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.payments.PaymentsExample"
```

The reference to copy when writing an automation that talks to anything outside its own context.
`ExecutePaymentAutomation` is the file to read: its `onFailure` maps each way a payment gateway can fail
onto an `AutomationFailureAction` *and* onto an event, with the reasoning for each pairing written down
next to it. The example runs all four paths — a payment that works, one rejected outright into the
dead-letter read model, one declined twice and deferred while the payments behind it proceed, and the
gateway going down entirely so the automation backs off and then catches up by itself. Note the two
distinct delays it shows, which are easy to conflate: `PaymentAttemptFailed.nextAttemptDueAt` defers **one
item** and is durable because it is an event, while `delayBeforeNextBatch` paces **the whole automation**
and deliberately is not.

## Architecture Patterns

### BoundedContext Pattern

The framework uses a builder pattern to create bounded contexts:

```java
BoundedContext.newBuilder(MyContext.class)  // MyContext extends BoundedContext<D,I,O>
    .name("context-name")
    .eventStorage(eventStorage)
    .instance(instance)
    .rootPackage(RootClass.class.getPackage())
    .build()
```

Key concepts:
- **BoundedContext**: Main entry point providing `execute()` and `read()` capabilities. Context interfaces extend `BoundedContext<D,I,O>` directly (e.g., `Banking extends BoundedContext<BankingEvent, BankingInboundEvent, BankingOutboundEvent>`)
- **ServiceLoader pattern**: Implementation discovery uses Java ServiceLoader (see `BoundedContext.newBuilder()`)
- **Three event types**: Domain events (internal), Inbound events (received), Outbound events (published)
- **Instance**: Deployment/tenant identifier created via `InstanceFactory.determine()`

**`build()` assembles, `start()` runs — and the processor threads follow that split:**
- **The threads are created by `start()`, not by `build()`.** `ProcessorThreadManager` used to submit
  every processor from its constructor, and the module constructors run inside `build()`, so a context
  that was built and never started still had a thread per registered read model, automation, translator
  and dispatcher. Nothing was gained by it: a processor is constructed `STOPPED` and does no work until
  `start()` says so — `AutomationProcessor`'s constructor says as much, "don't run before start() or
  things might not have been initialized in the bounded context impl", which was a `volatile` flag
  holding live threads off a half-built context. Now the threads do not exist to be held off
- **An idle processor is not free, which is what made this worth moving.** Both processor loops park in
  `Object.wait()` inside a `synchronized` block, and on Java 21 a monitor wait pins the carrier, so every
  parked virtual thread holds a platform thread. Measured before the change: 16 ephemeral read models
  took a JVM from 8 platform threads to 26 at `build()`, with the context never started
- **The processors are started before their threads are submitted**, which is load bearing rather than
  incidental. `Processor.start()` signals a thread that may not be parked yet, and the stopped branch of
  each loop re-checks only its terminating flag before waiting — so a signal arriving first is lost and
  the processor sits out a full poll interval (10s, or 30s while stopped) before noticing it was started.
  Starting first means the loop's very first pass reads a processor that is already running. The threads
  are submitted once, so the `stop()` → `start()` restart path still just signals the parked ones
- **A build that fails releases what it had made.** `build()` hands the caller nothing when it throws —
  no context, so no `terminate()` and no shutdown hook — so anything constructed by then is unreachable.
  This is an ordinary path, not a corner: all four registries reject a duplicate or anonymous component
  name from the middle of the sequence, and the modules built before the rejection have already
  subscribed their processors to their streams, which the event store holds until it is closed. `build()`
  now terminates the modules it got through and closes the store it opened, in reverse order, with any
  cleanup failure attached to the build failure as a **suppressed** exception rather than replacing it.
  The storage is untouched, as everywhere else — it came from outside
- **`build()` still does event-store I/O**, and this change does not address that: a `ProjectorProcessor`
  drops the stale bookmark of an `EPHEMERAL` read model and reads a durable one's own bookmark from its
  constructor, so building a context costs a round trip or two per read model before anyone asked it to
  run. Making `build()` genuinely side-effect-free means moving that (and the `subscribe`) into the
  processor's own thread, which is a larger change
- `ProcessorThreadManagerTest` pins the timing, the start-before-submit order, the single submission
  across a restart, and that terminating a never-started manager neither throws nor leaves a later
  `start()` able to put terminated processors back on threads. `FailedBuildReleasesWhatItBuiltTest` pins
  the cleanup, by counting what the failed build subscribed to the storage against what it gave back

### Feature Slice Pattern

Features are organized as vertical slices:

1. **@FeatureSlice annotation**: Classes annotated with `@FeatureSlice` are discovered via package scanning
2. **Slice interface**: Feature slices implement `Slice<C>` where `C` is the bounded context type (e.g., `Slice<Banking>`)
3. **Types of feature slices**:
   - `STATE_CHANGE`: Commands that change state
   - `STATE_READ`: Read models that project state
   - `AUTOMATION`: Process managers/sagas
   - `TRANSLATION`: Inbound event handlers
   - `OTHER`: Utility features

**Feature Slice Structure:**
```
features/
  openaccount/
    OpenAccountFeatureSlice.java      # Configuration class with @FeatureSlice
    OpenAccountCommand.java            # Command implementation
  accountdetails/
    AccountDetailsFeatureSlice.java
    AccountDetailsReadModel.java       # ReadModel implementation
  accountoverview/
    AccountOverviewFeatureSlice.java
    AccountOverviewReadModel.java
```

### Core Component Interfaces

**Commands:**
- Implement `Command<DOMAIN_EVENT_TYPE>`
- Return `CommandResult` containing raised events
- Access bounded context capabilities via constructor injection
- Unlike every other component, a command is not wired into the bounded context: it is instantiated by
  the caller, executed ad hoc, and attributed to its feature slice by package convention. A slice can
  still declare its commands from `configureCommand` with `builder.command(PlaceOrderCommand.class)`,
  which is purely declarative — it only adds them to the slice's `members` on `BoundedContextStarting`,
  so an observer (the dashboard) shows them from startup instead of after their first execution

**A command appends to exactly one stream — no command raises a domain event and an outbound event
together:**
- `AbstractCommand` is sealed with exactly two permits. `Command<D>` decides on domain events and
  raises domain events; `OutboundCommand<D,O>` decides on domain events and raises **outbound** events
  — the shape that feeds the outbound stream, and the only writer that stream has (there is no
  `ProvidedEventCapability` for outbound events; dispatchers only consume). The produced type fixes the
  target stream in `DCBModule`, and everything a command raises goes out in **one** `append` on that one
  stream. There is no third command shape, and no cross-stream atomic append in the eventstore SPI to
  build one on: domain and outbound are separate streams (purposes `domain` and `outbound`) whose
  appends commit independently
- **"Record the fact and publish it" is therefore an automation pattern, not a command shape — and
  `AutomationContext.publishAndRecord(outboundCommand, domainEvent, itemKey)` is how to write it.**
  The helper composes the two halves the one safe way, so the composition cannot be hand-rolled wrong:
  it executes the `OutboundCommand` under `<itemKey>/outbound`, then provides the domain event under
  `<itemKey>/domain`, and returns the domain event's reference — which is what `handle` should return.
  `itemKey` must be stable per todo item (derived from the item, never from the attempt) and is
  rejected when null or blank. Why that exact shape:
  - **Outbound first, domain second.** Only the domain event makes the todo list drop the item, so a
    crash between the two leaves the item outstanding and the retry re-runs both halves — the outbound
    half dedups on its key, and the domain event then lands. The other order loses the publication for
    good: the domain event completes the item, and nothing ever retries the outbound append
  - **Both events keyed, both keys derived from the todo item.** The outbound key is what turns the
    crash-window retry into a no-op; the domain key is what keeps a re-handled item (a crash between
    append and bookmark, or a failover overlap) from recording the fact twice. Two keys, because they
    are scoped per stream and guard two different appends. A repeat surfaces as `Optional.empty()`,
    which for an at-least-once caller is success — the work was already done
- **An `OutboundCommand` is not offered decision models, because they cannot guard its append.** The
  models would be projected from the domain stream, but the `AppendCriteria` they produce travels with
  the append — which runs against the *outbound* stream, where domain event types never occur, so the
  optimistic-locking check would match nothing and admit everything: a boundary that guards nothing,
  silently. This is enforced at the type level: `execute` moved off the sealed `AbstractCommand` onto
  the permits, and an `OutboundCommand` receives `OutboundCommandContext` — `read(...)` and
  `noDecisionModels()` only — while `CommandContext` extends it adding `decisionModels(...)` for the
  domain command, whose boundary genuinely guards its stream. (Making the models real instead would
  need a cross-stream conditional append the storage SPI does not have)
- **Every outbound event must carry an idempotency key, and an append without one is rejected** —
  `IllegalStateException` from `execute`, before anything is stored. The check runs in `DCBModule`
  after key resolution, so a key from any source satisfies it: per event
  (`raiseEvent(event, tags, key)`), command-level (`CommandResult.idempotencyKey(...)` and friends), or
  externally provided (`execute(command, key)` — what `publishAndRecord` does). The deliberate opt-out
  is `forbidIdempotencyKey()`, which declares the command publishes without de-duplication on purpose
  — greppable, and it costs exactly that: an at-least-once caller may publish twice
- **Where latency permits, prefer not needing the pair at all**: keep the command domain-only and derive
  the publication — a todo list projects the domain event and an automation executes the
  `OutboundCommand`. Command → domain event → todo list → automation → outbound event → dispatcher:
  every hop bookmarked, at-least-once and dedup-able, at the price of the dispatch lagging the fact
- `DispatchOrderAutomation` in the benchmark module is the in-tree example to copy: one
  `publishAndRecord` call, with its `RegisterOrderDispatched` declaring `requireIdempotencyKey()` so
  the item-derived key from the helper is mandatory rather than incidental. `OutboundCommandGuardsTest`
  pins the runtime guards down — the rejection stores nothing, each key source satisfies it, the
  opt-out really does forgo dedup, and `publishAndRecord` orders the appends, dedups a re-handled item
  and rejects a missing item key. The context narrowing is compile-time and needs no runtime pin

**Which read model to reach for is written down for users, and it is the same order to advise in.**
[CHOOSING-A-READ-MODEL.md](CHOOSING-A-READ-MODEL.md) carries the ladder — decision model, live model,
bound the replay, eventually consistent in memory, eventually consistent durable, seeded read,
snapshots — with what each step buys and costs, plus the shapes to steer away from. The sections below
are the *why* of each mechanism; that file is the *which*, and it is the one a user reads first. Keep
the two in step rather than restating one in the other, and when advising on a read model, say which
rung it is on and what would justify the next.

**How a read model is projected has to be said out loud.** `builder.readmodel(X.class)` and
`builder.readmodel(instance)` register, but `build()` rejects either unless `.live()` /
`.eventuallyConsistent()` (or `.snapshots(...)`, which implies live) was called, naming every offender
at once. The verb is redundant with the overload — a class can only be live, an instance only
eventually consistent — and that is the point: a mode that follows silently from which method was
called is one nobody had to decide, while the difference between the two is one every caller of that
read model lives with. **Registration stays eager on purpose**: registering lazily from `live()` would
turn a forgotten verb into a read model that silently is not there, trading a mistake caught at build
time for one that surfaces as a failing read. `ReadModelModeIsExplicitTest` pins it.

**ReadModels:**
- Implement `ReadModel<DOMAIN_EVENT_TYPE>` which extends `EventHandler<DOMAIN_EVENT_TYPE>`
- Define which events to handle via `when(EventType event)` methods
- Can be queried via `boundedContext.read(ReadModelClass.class, ...)`
- A read model declares where it keeps its state via `ReadModelWithMetaData.storage()`, returning a `ReadModelStorage`:
  - `EPHEMERAL` (default): in-memory, gone with the process. Every instance projects its own copy and stale bookmarks are dropped at startup
  - `LOCAL`: durable but private to one instance. Every instance projects its own copy and resumes from its own bookmark
  - `SHARED`: durable storage the whole deployment reads and writes. A single elected leader projects
- The storage class also decides how the read model is projected, so registration does not repeat it:
  `builder.readmodel(readModel).eventuallyConsistent()`. `SqlReadModelProjector` derives it from its
  DataSource (in-memory H2 → `EPHEMERAL`, anything else → `SHARED`); override `storage()` for a
  database that is durable but private to one instance
- `boundedContext.start()` does not return until every `EPHEMERAL` read model has been projected completely, so those are usable right after start. `LOCAL` and `SHARED` read models keep their bookmark and catch up in the background without blocking startup. The wait has a safety timeout (default 5 minutes, `-Dsliceworkz.eventmodeling.readmodel.ephemeral.projection.timeout.ms=...`) after which startup continues with a warning

**A durable read model keeps its own position, next to the state it projects:**
- **The problem it solves is a two-store commit with no transaction across it.** A read model writes its
  rows in its own database; the framework bookmarks its progress in the event store, *after* the commit.
  That ordering is right — a crash in the window costs a repeat, not a loss — but against a durable,
  shared read model a repeat is duplicated inserts and double-counted totals, permanently, with nothing
  raised. The window was never one batch either: the eventstore bookmark used to be written once per
  `run()`, so a catch-up that committed 2000 batches and died replayed all of them
- **`SelfBookmarkingProjection` is the answer, and `SqlReadModelProjector` implements it.** Every batch
  upserts the reference of its last event into a `<prefix>_projection_bookmark` table **inside the
  transaction that writes the rows**, so the state and the position become durable together and cannot
  disagree. Startup resumes from that table through `Projector.startingAfter(...)`, and the eventstore
  bookmark is written but never read (`readOnManualTriggerOnly`) — it stays as the record an operator and
  the dashboard see, lagging the truth by at most one batch
- **An absent row means replay from the beginning, and deliberately does not fall back** to the eventstore
  bookmark. That is what makes "drop the tables to rebuild" work with nothing else to reset, and what
  stops a fresh database being handed a position describing rows it does not have. It also fixes a case
  nobody had reported: an `EPHEMERAL` H2 read model whose in-memory database outlived its bounded context
  (`CREATE TABLE IF NOT EXISTS` plus the framework dropping the eventstore bookmark) used to duplicate its
  whole history on a context restart within one JVM
- The table is created by `ensureTables()` **and** on first use, because the order is not ours to pick:
  the context asks a read model where to resume while building its processor, possibly before the feature
  slice has ensured anything. `tracksItsOwnBookmark()` opts out and puts the read model back on the
  framework's bookmark
- **`project()` should still be idempotent wherever it cheaply can be**, because this covers the
  framework's own replay and the steady state only — leader election keeps a second instance from
  projecting a `SHARED` read model, but a failover window is at-least-once (a leader paused past its
  lease can commit a batch the new leader repeats). Two helpers do it:
  - `updateOnce(...)` — an UPDATE that applies at most once per event per row. **There is no
    one-row-per-event assumption**: `EVENT_REF_COLUMNS` on a row mean "the newest event this row
    reflects", so the ordinary aggregate — `(customer_id, total_order_count, last_order_date)` fed by a
    stream of `OrderReceived` — is exactly its case, as is one event updating many rows
  - `insertIfAbsent(...)` — creates the aggregate row so `updateOnce` has something to guard, and
    **deliberately leaves the freshness columns at their zero defaults**. Writing the current event's
    reference there is the natural-looking mistake: the `updateOnce` that follows would find the row
    already marked with that event and skip it, so the first order of every new customer would create the
    row and never be counted
  - `insertOnce(...)` — for the append-style table (a log, a list, a dead-letter view), keyed on
    `last_event_id` so it works where two events legitimately produce identical-looking rows. One event
    inserting *several* rows is the case it cannot cover
- **The comparison is the total `(tx, position, index)` order, never `last_event_position` alone**, for
  the same reason `EventReference.happenedAfter` and the DCB check are: a position is a `bigserial` and a
  transaction id an `xid8`, assigned independently, so an event can carry a lower position than one that
  committed before it. A position-only guard silently *discards* events it should apply — worse than the
  duplication it was meant to prevent, and rare enough to reach production. This is why the helpers exist
  rather than the comparison being left to each read model
- **`insertOnce`/`insertIfAbsent` are a SELECT then an INSERT, not `INSERT ... WHERE NOT EXISTS`**, and
  that is not stylistic. H2 — what an ephemeral read model runs on — caches that subquery's result across
  executions of the same statement within a transaction, so the second call inserts a row the first had
  already inserted and dies on the unique index. It passes against PostgreSQL either way, which is exactly
  how such a thing reaches production. `SqlReadModelBookmarkTest.severalEventsInOneBatchEachAppendTheirOwnRow`
  pins it
- `SqlReadModelBookmarkTest` covers the positions and the helpers against a real database;
  `SqlReadModelSurvivesRestartTest` reproduces the crash window end to end through a bounded context
  (project, remove the framework bookmark, restart — the row count must not double) and fails with 50
  rows against 25 without the wiring. `SqlReadModelTest` exposes `projectedUpTo()` and
  `restartedProjector()` so a framework user can assert the same thing
- Needs the matching eventstore fixes to be complete: the bookmark is placed per batch, and a batch whose
  commit fails takes the projector's cursor back with it instead of skipping those events. See the
  eventstore's `BatchAwareProjection` notes

**A seeded read model — as current as a live model, at the cost of the delta:**
- **The gap it fills.** A live model is always current and replays the whole history to be; an
  eventually consistent read model is already materialised and lags whatever its projector has not
  reached. Neither serves a read that is both too expensive to project live and too important to
  answer from a stale projection. A `SeededReadModel` is the two combined: it loads a base — the rows
  an SQL projector wrote, or the state an in-memory read model holds — and the framework projects it
  over **only the events after that base**. Registration is unchanged
  (`builder.readmodel(X.class).live()`); seeding is a property of the class, because unlike a snapshot
  there is nothing external to configure
- **One method, and it is the counterpart of `SelfBookmarkingProjection`.** `resumeFrom()` says where a
  *projector* resumes writing; `seed()` says where a *read* resumes projecting. Both answer with a
  position, and both are wrong in the same way if that position is not atomic with the state it
  describes. `ReadModelModule.projectLiveModel` calls `seed()` once, after construction with the read's
  parameters, and hands the result to `Projector.startingAfter(...)` — so the delta is paged, upcasted
  and ordered on the total `(tx, position, index)` order exactly as a full projection is. **A
  hand-rolled `query()` is what would risk the heap**: an unbounded query materialises its whole
  result, where the projector reads in batches of 500
- **Empty means "project everything", and that is the expensive mistake.** An account with no rows, a
  key absent from the model, a customer who has done nothing are all *no state loaded* plus
  `Optional.of(position)` — not `Optional.empty()`. Reporting empty for a base that exists is
  **correct**, so nothing fails and no test goes red; it just costs the entire event history on every
  read, and only once the stream is long enough to notice. This is why `LiveModelProjected` gained
  `seededAt`: a seeded read that reports no base looks exactly like an ordinary live model from the
  outside, and `metrics.eventsStreamed()` next to it says what that cost
- **The state and the position must be one observation**, and there are two supported ways to get one:
  - `SqlReadModelQuery.loadBaseAt(reader, loader)` for the SQL case. Rows and bookmark are two SELECTs,
    and whatever the projector commits between them lands in one and not the other — **read the
    bookmark first and the caller re-applies events its rows already contain; read it afterwards and
    the events committed in between are in neither the base nor the delta**. Both are silent, and which
    one you get depends only on the order the two reads happen to be written in. `loadBaseAt` reads the
    bookmark on either side of the load and retries while the two differ, which is sound because
    `SqlReadModelProjector` commits its rows and its bookmark in one transaction: an unchanged bookmark
    proves no batch landed. **Deliberately not an isolation level** — `REPEATABLE READ` would mean
    depending on something PostgreSQL and the H2 an ephemeral read model runs on do not implement
    alike, where this is portable and lock-free. The loader may therefore be called more than once and
    must *replace* what it loaded rather than add to it
  - `PublishingReadModel.published()` for the in-memory case, below
- **The fold has to be written once.** The base and the delta are projected by different code — an SQL
  `project()` writing rows, and an in-memory `when()` folding a DTO — and if they disagree the answer
  depends on how far the projector got, which is the one thing this exists to make impossible. Extract
  the rule as a pure function of `(state, event)` and use it from both sides (`BalanceFold` in the
  banking example is the shape). Where throughput forbids the read-modify-write that implies, keep them
  separate and pin the equivalence with a test
- **Two registrations are rejected at build time**, both silent failures otherwise: a `SeededReadModel`
  registered `eventuallyConsistent()` (that processor resumes from its bookmark, so `seed()` would never
  be called and the read model would look seeded and not be), and one registered with `snapshots(...)`
  (two complete answers to "where does this projection start", with no sensible precedence)
- **It buys the cost of a live model down, not the visibility rules away.** An event still in flight is
  withheld from every reader alike — on PostgreSQL by the `pg_snapshot_xmin` barrier — so a seeded read
  is exactly as fresh as a live model and no fresher. What it can offer that a live model cannot is
  proof: have the read model expose the reference it reached, and a caller that just executed a command
  checks its own write is included with `happenedBefore`
- `SeededReadModelTest` pins the property that matters — **the answer does not depend on how far the
  projector got** — from all three ends: a base that is behind, a base that is up to date, and no base
  at all. It also counts the delta, because a seed being ignored produces entirely correct answers and
  is otherwise invisible. `SqlReadModelSeedTest` pins the retry against a real database, including a
  batch committing in the middle of a base read

**`PublishingReadModel` — the in-memory base, and the template to copy:**
- Folds events into an **immutable** state and publishes that state together with the position it
  reflects, through a single volatile field, once per batch. Two problems, one answer: an ordinary read
  model mutates its own fields from `when` on the projector's thread while readers call its getters
  from theirs — so a reader can observe a half-applied batch — and a reader that wants to catch it up
  needs the state and the position as one observation. `published()` is that observation
- What a subclass writes is a state type, `initialState()`, `apply(state, event)` and `eventQuery()`.
  `beforeBatch`/`when`/`afterBatch`/`cancelBatch` are final: a batch is folded aside into `pending` and
  becomes visible only by replacing the published pair, so a cancelled batch republishes nothing and a
  batch matching none of our events leaves the position where it was — the projector read past events
  this read model does not hold, which says nothing about state it does not have
- **The state must never be mutated after `apply` returns it**, which is the whole basis of the
  guarantee: what a reader took is a value no later batch can alter under it. The cost is a copy per
  batch rather than per event, which is what makes it affordable; a model too large for even that wants
  a lock and a copy of the answer alone, not this class
- `initialState()` is called lazily rather than from the constructor, so a subclass may build it out of
  its own fields — a superclass constructor would run before those are assigned and hand the read model
  a state built from nulls
- Storage is `EPHEMERAL` and final. `PublishingReadModelTest` pins the batch lifecycle; the concurrency
  is not tested, because a passing race proves nothing — it follows from the publication being one
  volatile write of an immutable value

**`ReadModelResult<R>` — one type for "an answer and what it reflects":**
- `ReadModelResult(R data, EventReference upTo)` in `readmodels`, returned by
  `PublishingReadModel.published()` and by `SqlReadModelQuery`'s `queryListWithRef` /
  `querySingleWithRef`. It used to be two records for one concept — `PublishingReadModel.Published`
  and a `ReadModelResult` nested in `SqlReadModelQuery`, the general idea living in the SQL package —
  which is the kind of duplication that quietly becomes two diverging concepts
- **`upTo` is what the answer reflects; how it was arrived at is the producer's business, and the two
  producers differ in a way that matters.** `published()` reports the last event it actually folded, so
  it is exactly the position of the state handed back. `queryListWithRef` reports the newest event any
  *returned row* reflects, which is only a **lower bound** on how far the read model has been projected
  — later events may have touched other rows, or created rows the query did not select. Good enough to
  answer "is my write in this row", and **not** a base to seed from: a per-row reference cannot account
  for a row that does not exist yet, which is why `SeededReadModel` seeds from `loadBaseAt` or
  `published()` instead
- Compare it with `happenedBefore`, never on `position()` — the same total-order rule as everywhere else
- The rename is source-breaking for anyone who imported `SqlReadModelQuery.ReadModelResult` or called
  `eventReference()`. A record cannot be aliased, so there is no deprecation path that keeps such code
  compiling; the type moved and the accessor is now `upTo()`

**Automations:**
- Implement `Automation<DOMAIN_EVENT_TYPE, TODO_ITEM_TYPE>`
- Paired with `TodoListReadModel` to identify work
- Process outstanding todo items by executing commands
- **Delivery is at least once, and the todo list is the queue.** Nothing else records outstanding work:
  an item is whatever the todo list projects out of the event history, so an item that was not handled
  is still there on the next round and after every restart, and an item is only "done" once the events
  it raised make the projection drop it. Two consequences worth designing around — an item is handled
  again whenever a crash lands between the append and the bookmark (give the raised events an
  idempotency key where that would be wrong), and **no state kept outside events survives**, so an
  automation that marks something done only in memory sees it come straight back
- **A failing item no longer stops anything by default.** Every failure used to be caught at the batch
  level and answered by setting the processor to `STOPPED`, which nothing but starting the bounded
  context again ever undid. So one poison item — or one ordinary `OptimisticLockingException`, the
  routine DCB outcome that `DCBModule` rethrows out of `context.execute()` — retired the automation for
  the life of the process, with its items sitting outstanding, one WARN line and no bounded-context
  event to say so. Failures are contained per item now, and what one costs is the automation's own
  decision through `Automation.onFailure`, returning an `AutomationFailureAction`:
  - **All three retry the item** — the framework has nothing to drop it from. They differ only in what
    happens to *the rest of the work* while it waits its turn again:

    | | this item | the items behind it |
    |---|---|---|
    | `RETRY_ITEM` | on the next batch | wait for it |
    | `CONTINUE_AND_RETRY_ITEM_LATER` | on a later batch | handled now, ahead of it |
    | `STOP_AUTOMATION` | after a restart | wait for a human |

  - `RETRY_ITEM`: the default. Abandons the rest of this batch, keeps the automation running, and the
    batch is attempted again from the front next round
  - `CONTINUE_AND_RETRY_ITEM_LATER`: **the only action that lets work overtake**, and so the only one
    that gives up the order `streamItems` defined. What to return when items are independent of each
    other — it is what keeps one poison item from holding up everything behind it. Named at length on
    purpose: it is chosen deliberately, never by accident. Note what it costs when a *shared* dependency
    is down rather than one item being poison: every item in the window is attempted and fails, so a
    round is `batchSize` failing calls against something already struggling, where the default makes one
  - `STOP_AUTOMATION`: the old behaviour, now opt-in and only where a human is meant to intervene
  - **The default never lets work overtake a failure**, and needs no classification of the cause to
    decide that. `streamItems` defines the order and the framework honours it, so abandoning that order
    the moment something goes wrong would be odd, and the framework cannot tell whether the items behind
    a failing one depend on it. Of the two ways to be wrong, holding up independent items is a stall
    that shows in `AutomationStatus` and clears when the cause is dealt with; overtaking dependent ones
    produces wrong results and reports nothing
  - **There is no inline retry, and an item is never handed to `handle` twice within one batch.** A
    failure worth retrying in milliseconds is almost always about the call the handler made rather than
    about the todo item, so it belongs inside `handle` where the code knows what it just attempted —
    and retrying one item three times is the wrong granularity for the failure it was meant to serve
    anyway, since a storage outage dooms every item in the window. What the framework does instead is
    back the whole batch off, which is what happens between batches regardless: **immediately** when the
    todo list has moved in the meantime — so an optimistic-locking conflict, where another writer did
    append, comes straight back — and after the poll interval when nothing changed, which is exactly
    when waiting is right
  - **What the default costs is a poison item at the head of the list holding up the rest**, for as long
    as it keeps failing — running, making no progress, `itemsFailed` climbing with nothing handled. The
    two ways out are the ordinary ones: `CONTINUE_AND_RETRY_ITEM_LATER` where the items are independent,
    or an `onFailure` that records the failure as an event the todo list projects, which moves the item
    out of the way for good
- **There is no dead-letter queue, and the durable substitute is an event.** Override `onFailure` and
  record the failure as a domain event through the context; the todo list projects it and defers or drops
  the item, and a read model over those same events is the dead-letter view. Retry-with-delay is the same
  mechanism — project attempt events and have `streamItems` withhold anything not yet due. Anything the
  framework held beside the todo list instead would be both lost at restart and unable to influence what
  `streamItems` offers next, which is the part that matters: a permanently skipped item still occupies a
  slot in every batch window, so only the todo list can actually unblock the queue
- **`Automation.batchSize()` decides how stale the todo list may be while a batch runs** (default 50).
  Within a batch the todo list does not move — it is projected by its own processor on its own thread, so
  events raised while handling an item reach it afterwards. Between batches the processor bookmarks the
  last event it produced and will not start the next batch until the todo list has been projected past
  it. So `batchSize(1)` gives every item a todo list accounting for everything the previous item raised,
  which is what to choose when handling one item can cancel or supersede the ones behind it; the cost is
  a projection round trip per item
- **A todo list may also anticipate its own projection**, which gets the same effect inside a batch:
  items are pulled one at a time and the next is only taken once the current one has been handled (an
  explicit iterator, not a `Stream` pipeline whose laziness nothing stated), so `streamItems` can withhold
  items the just-raised events will cancel. Only ever as a shortcut to a conclusion the projection reaches
  by itself — see the restart rule above. `streamItems` also owns ordering: items are handled in the order
  it returns them, and the framework neither re-orders nor verifies
- **A batch goes straight round again only when it filled its window *and* moved its bookmark.** Without a
  bookmark move the catch-up guard has nothing to hold the processor against, so it used to re-read the
  same window at full speed: an automation whose `handle` returns `Optional.empty()` — explicitly allowed,
  and what an automation with a purely external effect does — spun at ~40M invocations a second against
  50 items, hammering whatever it called. It now waits like an empty batch
- **How long it waits is `Automation.delayBeforeNextBatch(consecutiveFailedBatches, lastFailure)`**, asked
  only when the processor has already decided to wait. The default is the 10s poll interval while nothing
  is failing, and doubles from there up to 5 minutes while batches keep failing without handling anything
  — so a dependency that is down for an hour costs a handful of attempts instead of one per todo-list
  update. Overriding it trades load on whatever is down against how late recovery is noticed, since a
  backed-off automation sits out the current delay before finding out the dependency is back. A `null` or
  negative duration falls back to the default with a WARN, and a throw is contained the same way
- **A batch that failed and handled nothing is held for that delay whatever the todo list does**, which
  is the one place the bookmark-moved fast path is deliberately not honoured: a changed todo list says
  nothing about whether the dependency the handler needs has recovered, and releasing on it would tie the
  retry rate to the traffic feeding the list — a busy system would hammer whatever is down rather than
  back off from it. This needs its own wait (`backOff`, not `waitForWork`): bookmark moves arrive as a
  bare `notify()` on the processor's monitor, so a parked thread is woken by any of them however it came
  to be parked, and skipping the flag check alone left the automation released by the very notification it
  was meant to ignore. `backOff` loops to its deadline and only shutdown or a stop cuts it short
- **`AutomationStatus.consecutiveFailedBatches` is what makes a stall visible.** `itemsFailed` cannot: a
  healthy automation accumulates failures too. A number that keeps climbing means running, retrying and
  getting nowhere, and it resets the moment a batch handles anything
- **`AutomationFailed` hands that stall to the surrounding infrastructure**, emitted once per batch that
  failed and handled nothing — never per item, which is the automation's own business through `onFailure`
  and would turn an outage into a flood. A batch that handled even one item is progress and emits nothing,
  however many others failed in it. The rate is bounded by the backoff, and the event carries
  `consecutiveFailedBatches` so the consumer picks its own alerting threshold rather than the framework
  picking one. There is no matching "recovered" event: an automation that gets going again emits
  `AutomationProcessed` with a non-zero `eventsHandled`
- **A bookmark notification names a reader, and most of them are not ours.** Every bookmark placed on the
  domain stream is announced to every subscriber, so an automation hears about an application's own
  `Projector`, a migration tool or an operator's script bookmarking the same stream — readers named by
  whoever wrote them, under no obligation to look like a `ProcessorIdentification`. `bookmarkUpdated` used
  to `parse` the reader before comparing it, so every foreign one threw `IllegalArgumentException`. The
  event store contains a listener failure, so nothing broke: it logged at ERROR with a stack trace instead,
  once per bookmark placement per automation, for a notification that was never this automation's business.
  The reader is now compared as the string the projector actually wrote
  (`processorIdentification.toString()`), which is also the stricter match — a round trip through `parse`
  is not the identity for a read model whose `readmodelName()` carries a `/` or a `[`, and such an
  automation would have compared unequal to its own todo list and waited out every poll interval instead of
  waking on it. `AutomationForeignBookmarkTest` pins both halves
- **An automation must be a named class, and its name must be unique** — as must a read model's, a
  translator's and a dispatcher's. See "Component names are bookmark keys" below for the shared rule; for
  an automation the same name additionally keys the metric tags and the `AutomationAdminCapability` id
- **…but it does not wait out that interval when the todo list has moved underneath it.** The bookmark
  notification was a bare `notify()`, so one arriving *while a batch was running* had nothing waiting to
  hear it and was lost; the processor then parked the full 10s over a todo list that had already changed.
  `monitoredBookmarkMoved` remembers it — set by `bookmarkUpdated`, cleared when a round reads the todo
  list, so it only ever means "changed since we looked". This is what keeps a bookmark-less batch from
  crawling: a backlog whose appends all de-duplicate on their idempotency key bookmarks nothing at all,
  and one poll per batch would put a 10s tax on every 50 items of it. A bookmark that has *not* moved
  still parks, so the anti-spin guarantee is untouched.
  `AutomationFailureRecoveryTest.aTodoListChangingDuringABatchIsNotWaitedOut` pins it, and fails by
  timeout without the flag
- `AutomationFailureRecoveryTest` pins all of this down: the default holds the order and keeps the
  automation running, `CONTINUE_AND_RETRY_ITEM_LATER` lets the items behind a failing one proceed, a
  transient failure is retried on the next batch and never twice within one, `STOP_AUTOMATION` does what
  it says, a no-event handler does not spin, and an item cancelling its successors under `batchSize(1)`
  really does prevent them being handled
- **The catch-up guard compares the total `(tx, position, index)` order**, through
  `EventReference.happenedAfter` in `AutomationProcessor.hasCaughtUp`, not `position()` alone. The two are
  genuinely different orders — a position is a `bigserial` and a transaction id an `xid8`, assigned
  independently, so an event can hold a lower position and a higher transaction than one that committed
  before it — and comparing positions reported the projector as caught up while it was not, re-reading a
  todo list that still held items already handled. `AutomationCatchUpOrderingTest` pins it, including the
  inclusive boundary (the event we produced counts as projected) and the `index` tiebreak upcasting produces
- **`ProvidedEventCapability.event(...)` takes an idempotency key**, so an automation raising events
  directly has the dedup lever a command already had through `CommandResult.idempotencyKey`. The key is
  scoped to the stream, and a repeat is silently ignored by storage — which surfaces as `Optional.empty()`,
  the same value as "not appended", the two being deliberately not distinguished (for an automation the
  work is done either way, and the todo list drops the item once the original event is projected). Derive
  the key from the todo item, never from the attempt, or every replay gets a fresh key and dedups nothing.
  The overloads are on the shared capability, so translators get them too. `ProvidedEventIdempotencyTest`
  covers it per backend plus end to end through an automation handed the same item twice
- **A stopped automation is visible and restartable, through `AutomationAdminCapability` on the bounded
  context.** `automations()` returns an `AutomationStatus` each — id, class, running, items failed,
  `lastFailure`, and `stoppedBy` (kept apart from `lastFailure`, because a running automation has usually
  survived failures and the one an operator wants is the one that stopped it). `restartAutomation(id)`
  puts a stopped one back, returning `false` if it was already running and throwing `IllegalArgumentException`
  naming the registered ids if there is no such automation. Two things to know: the item that stopped it is
  still at the head of the todo list, so restarting without fixing the cause handles it again and stops
  again; and this addresses **the instance it is called on**, since every instance runs its own processors
  — a remote channel is the same "name one instance" problem leader election has, so it is left out rather
  than half-done
- **`AutomationStarted` / `AutomationStopped` are the pair to fold to answer "is it running"**, the later
  of the two winning. `AutomationStarted` carries an `AutomationStartReason` (`BOUNDED_CONTEXT_START` or
  `RESTART`) and is emitted on the ordinary path too, so the running automations are announced from startup
  rather than from whenever each first has work — an automation with an empty todo list would otherwise say
  nothing at all and be indistinguishable from one that is not deployed. The two are deliberately **not**
  symmetric at shutdown: an automation going down with its context raises no `AutomationStopped`, because
  `BoundedContextStopping` already says so for all of them at once, which leaves `AutomationStopped`
  meaning the one state worth alerting on — down while its context is up
- **`AutomationStatus.itemsFailed` is counted separately from the meter of the same name**, deliberately.
  The default registry is an empty `Metrics.globalRegistry` composite whose counters are no-ops reading 0
  forever, so serving an operator's view from the meter would have made it depend on whether anyone wired
  up monitoring. `AutomationAdminTest` catches that (it asserts the count against an unconfigured registry)
- **A second instance no longer duplicates every item: automations run on the single elected leader.**
  See "Leader election" below for the mechanism, its configuration, and its honest limits
- **One automation is sequential end to end, and parallelism is realized across automations**: partition
  the work into several automation classes, each with its own todo list over a disjoint, stable share of
  the items (one todo list class can serve all of them under different `readmodelName()`s). Each runs on
  its own thread under its own lease, so partitions proceed in parallel — while ordering holds within one
  automation and nowhere else, which is why the partition key must keep dependent items together. The
  `Automation` interface javadoc carries the user-facing version of this

### Leader election — one instance per leader-only processor

**Every leader-only processor — an automation, a SHARED read model's projector, a translator, a
dispatcher — runs on exactly one instance of the deployment**, elected by holding a lease in the event
storage (the eventstore's `requestLease`/`releaseLease` SPI; see that repository's CLAUDE.md for the
lease semantics themselves). `LeaderElector`, one per bounded context on its own virtual thread, renews
every lease each heartbeat and flips `ProcessorInstanceMode` (`LEADER`/`STANDBY`) on the processors.

- **One lease per processor, named by its `ProcessorIdentification`** — the same string that keys its
  bookmark, so each lease guards exactly that bookmark's writer. An instance contends only for the
  elements it has deployed: a deployment that puts automation X on one instance and automation Y on
  another has each instance win the leases nobody else wants, where a single per-context lease would
  strand every processor the winning instance does not carry. `EPHEMERAL`/`LOCAL` read models are
  `RUNNING_ON_ALL_INSTANCES` and untouched by any of this — `start()`'s wait on ephemeral projections
  is unchanged
- **Election is never on the processing path.** Leadership is a `volatile` field the processors read at
  the top of every loop pass — the check that always existed, now actually flipped. No batch,
  projection or event query ever waits for a lease call; a leader processes continuously across
  renewals, so election adds zero read-model lag. On Postgres the lease tables sit outside the event
  log: no lock an event query or append takes, no interaction with the `pg_snapshot_xmin` barrier
- **Configuration on the builder**: `.leadershipPriority(long)` (default 0) and
  `.leadershipIntervals(heartbeat, ttl)` (defaults 5s/20s, `ttl >= 2×heartbeat` enforced). A live
  contender with a **strictly higher** priority makes the current leader finish its batch, park, and
  hand the lease over one heartbeat later — the fail-back path when a preferred instance returns.
  Equal priorities never preempt, which keeps a symmetric deployment stable
- **The safety rule is demote-before-takeover.** Expiry is judged on the storage's clock only; the
  elector measures durations on its own clock only. A leadership that cannot be *confirmed* is given up
  at `ttl - heartbeat` since the last successful renewal, while a challenger acquires no earlier than
  `ttl` — so on a crash, work pauses for up to the ttl (nothing is lost: it all sits in the event store
  and todo lists) rather than ever running twice. `start()` runs one synchronous election round before
  the processors' first pass; `stop()`/`terminate()` release the held leases so a standby takes over
  promptly instead of waiting out the ttl
- **A processor that stops itself hands its lease back.** A projector retired by a `ProjectorException`
  and an automation stopped through `STOP_AUTOMATION` set themselves `STOPPED` while their context — and
  its elector — keep running, and the elector used to renew their leases unconditionally: the stopped
  processor held its lease for the life of the process, so the read model was not projected, the
  outbound stream not dispatched or the automation not run *anywhere in the deployment*, on one WARN
  line. The elector now consults `Processor.stoppedItself()` each round: a self-stopped leader is
  demoted, its lease released (`LeadershipReleased`, reason `PROCESSOR_STOPPED`) and no longer contended
  for — not even as a contender, so a self-stopped high-priority instance does not step a healthy
  leader down — until the processor is started again, which puts this instance back in the race on the
  next heartbeat. That is why restarting a self-stopped automation resumes via re-election (within a
  heartbeat when nobody took over) rather than instantly. The check is deliberately *stopped itself*,
  not *is stopped*: every processor is lifecycle-`STOPPED` during the elector's synchronous first round
  at `start()`, which must still elect a leader before any processor's first pass.
  `LeaderElectionTest.testASelfStoppedAutomationHandsItsLeaseToAHealthyInstance` and its projector twin
  pin both paths, and fail by timeout without the release
- **Promotion re-seeds a projector.** The `Projector` holds its cursor in memory, so after a spell as
  standby that cursor describes where *this instance* stopped reading while the old leader projected
  on. `ProjectorProcessor` rebuilds its projector on every STANDBY→LEADER transition, re-running the
  resume logic — the shared bookmark, or `SelfBookmarkingProjection.resumeFrom()` for a self-bookmarking
  read model. `AutomationProcessor` needs nothing: it re-reads both bookmarks every round.
  `LeaderElectionTest.testPromotedProjectorResumesFromTheSharedPositionNotItsStaleCursor` fails without
  the re-seed
- **The lease owner identifies the elector, not the JVM.** `Instance.process()` is computed once per
  process, so two bounded-context instances in one JVM (a test, or deliberate co-location) would share
  an owner id and each mistake the other's lease for its own — both leaders. The elector suffixes a
  random token per construction; fail-back rides on priority, never on owner identity, so a fresh owner
  per elector costs nothing
- **A storage without lease support falls back to the old behaviour**: every leader-only processor is
  promoted on this instance, with one WARN that a second instance would duplicate work. A third-party
  `EventStorage` predating leases keeps working unchanged; the in-memory storages implement leases, so
  a single process wins everything trivially rather than falling back
- **Observability**: `LeadershipAcquired`/`LeadershipReleased` (`BoundedContextEvent`s, per processor;
  reasons `STEPPED_DOWN`, `LOST`, `RENEWAL_FAILED`, `STOPPED`, `PROCESSOR_STOPPED`). Deliberately not
  emitted at shutdown —
  `BoundedContextStopping` already says it for everything at once, the same asymmetry
  `AutomationStopped` keeps. `AutomationStatus` gained `leader`: `running` says the automation would
  process if elected, `leader` says it actually is on this instance; `restartAutomation` on a standby
  restarts it to standing by, not to work
- **What leader election deliberately does not promise: exactly-once through a failover.** A leader
  paused beyond its ttl (GC, VM freeze) can finish a batch it had already started while the new leader
  begins — a bounded overlap no lease can prevent. What contains it is what already existed: idempotency
  keys on automation-raised events (derive them from the todo item), `updateOnce`/`insertOnce` in SQL
  read models, and DCB conflicts. The lease's fencing token is stored and surfaced (on
  `LeadershipAcquired` and `getLeases()`) but not yet enforced inside `SqlReadModelProjector`'s batch
  transaction — that is the designed next step if zombie writes to SHARED SQL read models must fail hard
- `LeaderElectionTest` pins it end to end with two instances in one JVM: only the elected leader
  handles todo items (per backend), failover hands work over without loss or duplication, a
  higher-priority instance regains leadership through the step-down protocol, a re-promoted projector
  resumes from the durable position instead of its stale cursor, and a lease-less storage falls back
  loudly. The lease semantics themselves are pinned per backend by the eventstore's `LeaseTest`

**Translators:**
- Implement `Translator<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE>`
- Convert external events to domain events
- Registered with the bounded context via a `@FeatureSlice(type = TRANSLATION)` slice (`builder.translator(...)`)
- Two ways to run a translation, both reusing the same registered `Translator` implementations:
  - `boundedContext.incoming(inboundEvent)`: eventually-consistent. Appends the inbound event to the inbound stream; matching translators run asynchronously via projectors. Supports idempotency keys.
  - `boundedContext.translate(inboundEvent)`: interactive. Runs every matching translator synchronously in the calling thread, does **not** persist the inbound event, and returns the `List<EventReference>` of domain events raised. Throws `NoTranslatorRegisteredException` if no registered translator matches the event. Useful for integration scenarios that need to act immediately on the produced domain events.

**Dispatchers:**
- Implement `Dispatcher<OUTBOUND_EVENT_TYPE>`
- Implement outbox pattern for publishing events
- Registered with the bounded context via `builder.dispatcher(...)`, and subject to the naming rule below.
  This is the registry where getting a name wrong costs the most, since the bookmark records what has
  already been published to an external system

### Component names are bookmark keys

**A read model, an automation, a translator and a dispatcher are each identified by a name**, which
becomes the `id` in `ProcessorIdentification` and therefore the reader name of the bookmark recording how
far that component has been projected. The name is the class' simple name, except for a read model, which
may override `readmodelName()` — which is how one read model class serves several instances under
different names. Two properties are load-bearing, nothing further down checks either, and both are now
enforced at build time by `ProcessorNames`, the one validator all four registries run their components
through:

- **Unique within its kind.** Two components sharing a name share one bookmark, so each advances it past
  events the other never saw. Nothing throws and nothing is logged: the events are simply never handled,
  and for a dispatcher that means never published. This is why it cannot be left to a naming convention
- **Stable across restarts.** A name a class cannot supply the same way twice gives a fresh bookmark on
  every start, so the whole stream is handled again from the beginning at every boot. An anonymous class
  has no simple name at all; a generated one (a lambda, a proxy, a bytecode-generated subclass) has a name
  like `Foo$$Lambda/0x00007f...` regenerated per JVM run. For a dispatcher that is duplicate publishing to
  an external system on every restart — the worst outcome the framework has

The shape of the class is only held against a component **when the name it supplied is its class' simple
name**, i.e. when it did not name itself. An anonymous read model returning a stable `readmodelName()` is
perfectly able to key a bookmark and is accepted; an anonymous one falling back to the default is not.

Two of these used to be missing entirely. Dispatchers had no duplicate check at all, and neither
dispatchers nor translators had the shape check — an anonymous one failed deeper down with a bare
`id is required` naming neither the component nor the reason. `DuplicateDispatcherNameTest`,
`DuplicateTranslatorNameTest`, `DuplicateAutomationNameTest` and `DuplicateReadModelNameTest` pin the
four registries down; they are plain `@Test`s, since this is framework behaviour rather than storage
behaviour.

**BoundedContextListener — observability that cannot fail the work it observes:**
- Register one on the builder (`.listener(...)`) to receive every `BoundedContextEvent` the kernel
  produces. `StreamAppendingBoundedContextListener` appends them to a stream, which means the listener
  does I/O on the caller's thread and can fail exactly like any other event-store call
- **A listener failure is never the caller's failure, and never silent.** `BoundedContextEventEmitter`
  contains every delivery: the exception is caught, counted on
  `sliceworkz.eventmodeling.listener.failure` (tagged `context` and `event`) and logged at ERROR, and
  the operation carries on as if no listener were registered. Unguarded, the throw was not merely noise
  at three call sites:
  - `CommandExecuted` is emitted **after** the command's domain events are durably appended, so a
    throw reported a command that had succeeded as failed — and the caller's natural response to that
    is to execute it again. Worse, it landed in `DCBModule`'s own `catch ( RuntimeException )`, which
    then emitted `CommandFailed` for that same successful command: the observability record said the
    opposite of what happened
  - In `AutomationProcessor`, `AutomationProcessed` is emitted **before** the processor bookmarks the
    events the batch produced, so a throw skipped the bookmark and the processor re-read a todo list it
    had already worked
  - A projector's run listener (`EventuallyConsistentReadModelUpdated`) throws *inside* the projection
    loop rather than beside it
- The two processor loops have a catch-all of their own, so they degraded into an error-and-retry cycle
  rather than dying — which is exactly why containment belongs at the emitter: a loop's catch-all cannot
  tell a broken listener apart from a broken projection, and answers both by abandoning the round
- **`Error` is deliberately not caught**, matching the eventstore's rule for its own append listeners:
  an exhausted heap is not a listener problem to absorb
- **Nothing replays what a failing listener missed.** The event is dropped and the next one is delivered
  normally, so the stream a listener writes is a best-effort record. A listener that must not lose
  events buffers and retries inside its own implementation
- **The log is throttled, the meter never is.** This sits on the hot path of every command, so a
  listener broken by a storage outage fails once per command and a stack trace each would bury the cause
  under its own symptoms. The first failure of a run logs in full; identical repeats are counted and
  summarised at most once a minute carrying the suppressed count; a different exception type reports
  immediately; recovery logs a WARN naming how many events were lost. Alert on the meter, which keeps
  the exact rate
- **A listener that always throws does not stop the context coming up.** Failing the boot would turn a
  transient blip in whatever the listener writes to into an outage of the application it only observes
- `BoundedContextListenerFailureTest` pins all of this down: a command whose `CommandExecuted` delivery
  throws still appends and still returns its reference, is never reported as `CommandFailed`, the
  delivery after a failing one still arrives, every failure is counted, and the projector and automation
  both keep making progress

## Event Modeling Core Templates

The framework supports the 4 Event Modeling patterns:

1. **State Change**: Trigger → Command → Event
2. **State Read**: Events → ReadModel → UI/API
3. **Automation**: Events → TodoList → Processor → Command → Event
4. **Translation**: External Event → Processor → Command → Event

## Naming Conventions

**Classes:**
- Commands: `*Command` (e.g., `OpenAccountCommand`)
- ReadModels: `*ReadModel` (e.g., `AccountDetailsReadModel`)
- Automations: `*Automation` (e.g., `ProcessPaymentAutomation`)
- Feature slices: `*FeatureSlice` (e.g., `OpenAccountFeatureSlice`), implementing `Slice<Context>` directly
- Bounded context interfaces: Short names extending `BoundedContext<D,I,O>` (e.g., `Banking`, `OrderProcessing`)
- Domain model: Often named `*Domain` (e.g., `BankingDomain`)

**Events:**
- Past-tense records (e.g., `AccountOpened`, `MoneyDeposited`)
- Typically defined as sealed interfaces with record implementations

**Packages:**
- Root: `org.sliceworkz.eventmodeling.*`
- Examples: `org.sliceworkz.eventmodeling.examples.{domain}`
- Features: `org.sliceworkz.eventmodeling.examples.{domain}.features.{featurename}`

## Testing Approach

**Test Module:**
- `sliceworkz-eventmodeling-tests`: the framework's integration tests, run against every event storage the eventstore ships

**Running against every backend:**
The suite builds on `sliceworkz-eventstore-testing`, the eventstore's published test support:
- A scenario that must hold whatever the storage is is annotated `@ForEachBackend` instead of `@Test`. It runs once per registered `EventStoreBackend`, and each invocation is reported under the backend that produced it (`myScenario [postgres:18]`)
- The backend set is data, not code: `src/test/resources/META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend` lists `InMemoryBackend`, `InMemoryFsBackend`, `Postgres17Backend`, `Postgres18Backend`. Adding a storage to the matrix is a line in that file and nothing else
- A plain `@Test` runs once against the in-memory store. Use it when the scenario is about the framework rather than about storage behaviour (duplicate-name validation, listener wiring), so it does not cost a container run per backend
- Skip the containers in a local run: `mvn test -Deventstore.testing.backends=inmem`

**Base Classes:**
- `org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractBoundedContextTest` extends the eventstore's `AbstractEventStoreTest`, so it owns the storage lifecycle (fresh empty store per test) and the bounded-context release. Subclasses reach the store through `eventStorage()` and must not build one themselves. The release *terminates* the context rather than stopping it, because terminating is what closes the `EventStore` the context built and drains its processor threads — see "Shutdown — who closes what" below
- Framework users extend the base test classes published in `sliceworkz-eventmodeling-testing` (`CommandTest`, `AggregateTest`, `LiveModelTest`, `SqlReadModelTest`)
- Use JUnit 5 (Jupiter)

**The published base classes run the same matrix, and that is the point of them being the same mechanism:**
- `sliceworkz-eventmodeling-testing`'s `AbstractBoundedContextTest` — the base of `CommandTest`,
  `AggregateTest` and `LiveModelTest` — extends the eventstore's `AbstractEventStoreTest`, exactly as
  this suite's own `mock.boundedcontext.AbstractBoundedContextTest` does. So a user annotates a scenario
  `@ForEachBackend` and it runs against every `EventStoreBackend` they registered, and a plain `@Test`
  runs once against the in-memory store. Same annotations, same service file, same
  `-Deventstore.testing.backends=` narrowing as here
- **It used to build an `InMemoryEventStorage` of its own**, which made the framework's matrix
  unreachable from outside this repository: a user testing a command against the PostgreSQL they deploy
  on had to abandon the base classes and wire a bounded context by hand. The two base classes had drifted
  into different mechanisms, and only the unpublished one could reach the backends — the storage-specific
  outcomes these tests exist to catch (DCB conflicts under a real advisory lock, tag round trips through
  `text[]`, ordering where position and transaction disagree) were the ones a user could not reach
- **A plain `@Test` behaves exactly as before**, deliberately: `createEventStorage()` falls back to the
  in-memory store when no backend is bound, so every test written against these classes keeps running
  with nothing to change, and `@ForEachBackend` is the opt-in
- What a user still has to supply is what this module supplies too — the service file plus the storage
  the named backends build on (`sliceworkz-eventstore-infra-postgres` and friends are `<optional>` in
  `sliceworkz-eventstore-testing`, so they are not inherited). `InMemoryBackend` needs nothing further.
  See `sliceworkz-eventmodeling-testing/README.md`
- `CommandTestRunsOnEveryBackendTest` and `LiveModelTestRunsOnEveryBackendTest` are this repository's
  copy of what a user gets: they extend the *published* bases, run `@ForEachBackend` over the whole
  matrix, and keep one plain `@Test` to pin the in-memory default. Without the wiring they would run
  in-memory only — which is precisely the failure that went unnoticed, since nothing about a green
  in-memory run says the other backends were never asked

## EventStore Integration

The framework depends on the separate `sliceworkz-eventstore` library:

**Key Concepts:**
- `EventStorage`: SPI for persistence (PostgreSQL, in-memory)
- `EventStore`: Main API created via `EventStoreFactory.get().eventStore(storage)`
- `EventStream`: Typed event stream for bounded context
- `EventQuery`: Query events with filters
- `EventReference`: Pointer to specific event by ID

**Creating EventStore:**
```java
EventStorage storage = InMemoryEventStorage.newBuilder().build();
EventStore eventStore = EventStoreFactory.get().eventStore(storage);
```

**EventStream Usage:**
```java
EventStream<DomainEvent> stream = eventStore.getEventStream(
    EventStreamId.forContext("context-name").withPurpose("domain"),
    DomainEvent.class
);
```

**Shutdown — who closes what:**

`EventStorage` and `EventStore` are `AutoCloseable`, and closing them releases real resources
(the Postgres backend's LISTEN/NOTIFY monitor threads and their connections; a store's notification
executors). Ownership decides who closes which:

- The bounded context builds its own `EventStore` over the storage it is given, and closes that store
  on `terminate()`. It never closes the storage: that came from outside, one storage can back several
  contexts, and it usually outlives them.
- The storage is the caller's to close, after terminating every context on it. Anything the caller
  supplied to the storage (a DataSource, say) is closed after the storage, not before.
- A bounded context also terminates itself from a JVM shutdown hook, so a process that just exits
  strands nothing. Terminating explicitly is what an application or test that outlives its context
  must do — and it deregisters that hook, so the context can be collected.
- Operations on a closed storage — or on streams from a closed store — throw
  `EventStorageClosedException` rather than quietly reading on, since a projector whose notifications
  have stopped would otherwise stall unnoticed.

```java
EventStorage storage = PostgresEventStorage.newBuilder().dataSource(pool).build();
Banking bc = BoundedContext.newBuilder(Banking.class).eventStorage(storage)/* ... */.build();
bc.start();
...
bc.terminate();   // closes the store the context built; storage untouched
storage.close();  // ours to close, once no context is running on it
pool.close();     // supplied by us, so closed after the storage
```

## Important Design Principles

1. **ServiceLoader discovery**: Implementation classes are discovered via ServiceLoader, not direct instantiation
2. **Sealed interfaces for events**: Use sealed interfaces for type-safe event hierarchies
3. **Immutable events**: Events are records and immutable
4. **Package scanning**: Feature slices discovered by scanning rootPackage for `@FeatureSlice`
5. **Builder pattern**: Bounded contexts created via fluent builder API
6. **Instance-based**: All operations tied to an Instance (deployment/tenant identifier)

## License

Project uses LGPL-3.0 license with headers enforced via mycila license plugin during package phase.
