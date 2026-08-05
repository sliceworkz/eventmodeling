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
- Still missing, deliberately out of scope here: leader election (`instanceMode` is hardcoded to `LEADER`
  while the bookmark is `[shared]`, so a second instance duplicates every item)

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
