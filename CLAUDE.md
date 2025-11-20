# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based Event Modeling framework implementing event-sourcing patterns with a focus on vertical slice architecture. The project is organized as a Maven multi-module build using Java 21.

**Core Modules:**
- `sliceworkz-eventmodeling-api`: Public API defining interfaces and abstractions
- `sliceworkz-eventmodeling-impl`: Framework implementation using ServiceLoader pattern
- `sliceworkz-eventmodeling-testing`: Base classes and utilities for testing
- `sliceworkz-eventmodeling-tests-inmem`: Integration tests using in-memory event storage
- `sliceworkz-eventmodeling-tests-postgres`: Integration tests using PostgreSQL
- `sliceworkz-eventmodeling-examples`: Example applications (banking domain)
- `sliceworkz-eventmodeling-bom`: Bill of Materials for dependency management
- `sliceworkz-eventmodeling-parent-pom`: Parent POM with shared configuration

**External Dependencies:**
- Uses `org.sliceworkz:sliceworkz-eventstore` library (version 0.3.3) for event storage abstraction
- EventStore provides PostgreSQL and in-memory implementations

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
BoundedContext.newBuilder(DomainEventType.class, InboundEventType.class, OutboundEventType.class)
    .name("context-name")
    .eventStorage(eventStorage)
    .instance(instance)
    .rootPackage(RootClass.class.getPackage())
    .build(BoundedContextInterface.class)
```

Key concepts:
- **BoundedContext**: Main entry point providing `execute()` and `read()` capabilities
- **ServiceLoader pattern**: Implementation discovery uses Java ServiceLoader (see `BoundedContext.newBuilder()`)
- **Three event types**: Domain events (internal), Inbound events (received), Outbound events (published)
- **Instance**: Deployment/tenant identifier created via `InstanceFactory.determine()`

### Feature Slice Pattern

Features are organized as vertical slices:

1. **@FeatureSlice annotation**: Classes annotated with `@FeatureSlice` are discovered via package scanning
2. **FeatureSliceConfiguration interface**: Slices implement this to configure themselves
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

**ReadModels:**
- Implement `ReadModel<DOMAIN_EVENT_TYPE>` which extends `EventHandler<DOMAIN_EVENT_TYPE>`
- Define which events to handle via `when(EventType event)` methods
- Can be queried via `boundedContext.read(ReadModelClass.class, ...)`

**Automations:**
- Implement `Automation<DOMAIN_EVENT_TYPE, TODO_ITEM_TYPE>`
- Paired with `TodoListReadModel` to identify work
- Process outstanding todo items by executing commands

**Translators:**
- Implement `Translator<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE>`
- Convert external events to domain events

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
- Feature slices: `*FeatureSlice` (e.g., `OpenAccountFeatureSlice`)
- Bounded context interfaces: `*BoundedContext` (e.g., `BankingBoundedContext`)
- Domain model: Often named `*Domain` (e.g., `BankingDomain`)

**Events:**
- Past-tense records (e.g., `AccountOpened`, `MoneyDeposited`)
- Typically defined as sealed interfaces with record implementations

**Packages:**
- Root: `org.sliceworkz.eventmodeling.*`
- Examples: `org.sliceworkz.eventmodeling.examples.{domain}`
- Features: `org.sliceworkz.eventmodeling.examples.{domain}.features.{featurename}`

## Testing Approach

**Test Modules:**
- `tests-inmem`: Uses in-memory event storage (fast, no external dependencies)
- `tests-postgres`: Uses PostgreSQL via Testcontainers (integration tests)

**Base Classes:**
- Tests extend framework-provided base test classes from `sliceworkz-eventmodeling-testing`
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

## Important Design Principles

1. **ServiceLoader discovery**: Implementation classes are discovered via ServiceLoader, not direct instantiation
2. **Sealed interfaces for events**: Use sealed interfaces for type-safe event hierarchies
3. **Immutable events**: Events are records and immutable
4. **Package scanning**: Feature slices discovered by scanning rootPackage for `@FeatureSlice`
5. **Builder pattern**: Bounded contexts created via fluent builder API
6. **Instance-based**: All operations tied to an Instance (deployment/tenant identifier)

## License

Project uses LGPL-3.0 license with headers enforced via mycila license plugin during package phase.
