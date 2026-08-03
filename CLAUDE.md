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
  - `SKIP_ITEM` (the usual answer): leave the item, carry on with the rest of the batch. It comes round
    again later. Right whenever items are independent of each other, poison ones included
  - `RETRY_ITEM`: hand the same item back to `handle` immediately, 3 attempts total with a doubling
    100ms backoff, then treated as `SKIP_ITEM`. For failures a second attempt can plausibly clear
  - `STOP_BATCH`: abandon the rest of this batch, keep the automation running. For ordered work, where
    the items behind the failing one are not independent of it and must wait rather than proceed
  - `STOP_AUTOMATION`: the old behaviour, now opt-in and only where a human is meant to intervene
  - The default (`AutomationFailureAction.defaultFor`) scans the cause chain: `OptimisticLockingException`
    → `SKIP_ITEM` (another writer moved the boundary, so re-reading the todo list is the repair, not a
    retry), `EventStorageException` → `RETRY_ITEM`, serde failures → `SKIP_ITEM` (identical on every
    attempt), anything else → `SKIP_ITEM`
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
- `AutomationFailureRecoveryTest` pins all of this down: a failing item at the head of the list does not
  stop the items behind it, a retriable failure is retried inside the batch, `STOP_BATCH`/`STOP_AUTOMATION`
  do what they say, a no-event handler does not spin, and an item cancelling its successors under
  `batchSize(1)` really does prevent them being handled
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
- Still missing, deliberately out of scope here: leader election (`instanceMode` is hardcoded to `LEADER`
  while the bookmark is `[shared]`, so a second instance duplicates every item), and a bounded-context event
  for a stopped automation (only the `sliceworkz.eventmodeling.automation.items.failed` counter exists)

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
