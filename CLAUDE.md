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
