# Implementation Plan: Optional Live Model Snapshotting

## Problem

Live models are rebuilt from scratch on every `read()` call by replaying all matching events through a `Projector`. For read models with large event histories, this becomes a performance bottleneck. Aggregates already solve this with snapshotting — we apply the same techniques to live models.

## Design Principles (mirroring aggregate snapshotting)

| Aggregate Snapshotting | Live Model Snapshotting (proposed) |
|---|---|
| `SnapshotCapable<T>` on aggregate | `SnapshotCapable<T>` on read model (reused) |
| `SnapshotStorage<T>` SPI | Same `SnapshotStorage<T>` SPI (reused, no changes) |
| `SnapshotSpecification` builder API | `LiveModelSnapshotSpecification` builder API (new, mirrors it) |
| `AggregateSpecification.snapshots(storage)` | `LiveModelSpecification.snapshots(storage)` |
| Key from `name + Tags` | Key from `readModelName + constructorParams` |
| `AggregateContextImpl` does load/save | `ProjectLiveModelCommand` does load/save |
| `AggregateModule` orchestrates | `ReadModelModule` orchestrates |
| Event count threshold triggers save | Event count threshold triggers save |
| `readAndWrite() / readOnly() / writeOnly()` | Same modes |
| Micrometer metrics | Same pattern of metrics |

## Changes by Module

---

### 1. `sliceworkz-eventmodeling-api` (public API)

#### 1a. Modify `SnapshotCapable<T>` — add overloaded `key()` for constructor-param-based identity

**File:** `snapshots/SnapshotCapable.java`

Add a second `default` key method alongside the existing Tags-based one:

```java
default String key(String name, Object... constructorParams) {
    StringBuilder keyBuilder = new StringBuilder(name != null ? name : "");
    if (constructorParams != null) {
        for (Object param : constructorParams) {
            keyBuilder.append("/");
            keyBuilder.append(param != null ? param.toString() : "");
        }
    }
    return keyBuilder.toString();
}
```

This keeps `SnapshotCapable` as the single marker interface for both aggregates and live models. The Tags-based `key(String, Tags)` remains untouched. Read models use the `key(String, Object...)` overload. Both can be overridden for custom key logic.

**Rationale:** Reusing `SnapshotCapable` rather than creating a new interface means:
- Read models and aggregates share the same contract (`takeSnapshot`, `fromSnapshot`, `version`)
- The `SnapshotStorage<T>` SPI needs zero changes
- Existing `SnapshotSpecificationImpl` logic can be reused

#### 1b. New `LiveModelSnapshotSpecification<D,I,O>` interface

**File:** `snapshots/LiveModelSnapshotSpecification.java` (new)

```java
public interface LiveModelSnapshotSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

    LiveModelSnapshotSpecification<D, I, O> eventCountThreshold(int threshold);

    BoundedContextBuilder<D, I, O> readAndWrite();
    BoundedContextBuilder<D, I, O> readOnly();
    BoundedContextBuilder<D, I, O> writeOnly();
}
```

Mirrors `SnapshotSpecification` exactly, just returns to the builder via a different path. This is the fluent API returned after calling `.snapshots(storage)` on a live model registration.

#### 1c. Modify `LiveModelSpecification<D,I,O>` — add `snapshots()` entry point

**File:** `readmodels/LiveModelSpecification.java`

Add:
```java
<SNAPSHOT_TYPE> LiveModelSnapshotSpecification<D, I, O> snapshots(SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage);
```

This creates the same entry point pattern as `AggregateSpecification.snapshots(storage)`.

---

### 2. `sliceworkz-eventmodeling-impl` (implementation)

#### 2a. New `LiveModelSnapshotSpecificationImpl`

**File:** `module/snapshots/LiveModelSnapshotSpecificationImpl.java` (new)

Direct mirror of `SnapshotSpecificationImpl`. Holds:
- `SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage`
- `READ_AND_OR_WRITE readAndOrWrite` (reuse the existing enum from `SnapshotSpecificationImpl`)
- `int eventCountThreshold` (default 100)

Returns to `BoundedContextBuilder` on terminal methods.

#### 2b. Modify `BoundedContextBuilderImpl.LiveModelSpecificationImpl`

**File:** `module/boundedcontext/BoundedContextBuilderImpl.java`

Add snapshot state to the inner class:

```java
class LiveModelSpecificationImpl implements LiveModelSpecification<D, I, O> {
    // existing fields...
    private LiveModelSnapshotSpecificationImpl<?,D,I,O> snapshotSpecification;

    @Override
    public <SNAPSHOT_TYPE> LiveModelSnapshotSpecification<D, I, O> snapshots(
            SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage) {
        this.snapshotSpecification = new LiveModelSnapshotSpecificationImpl<>(builder, snapshotStorage);
        return snapshotSpecification;
    }

    // Accessor methods for ReadModelModule to use:
    public SnapshotStorage<Object> snapshotStorage() { ... }
    public boolean readSnapshots() { ... }
    public boolean writeSnapshots() { ... }
    public int snapshotEventCountThreshold() { ... }
}
```

#### 2c. Modify `ReadModelModule` — pass snapshot config, store per-readmodel info

**File:** `module/readmodels/ReadModelModule.java`

The constructor and `liveModel()` method need to know about snapshot configuration per live model class. Instead of just holding `Collection<Class<?>>` for live models, hold a map of `LiveModelInfo` records (same pattern as `AggregateModule.AggregateInfo`):

```java
record LiveModelInfo<DOMAIN_EVENT_TYPE>(
    Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass,
    SnapshotStorage<Object> snapshotStorage,
    boolean readSnapshots,
    boolean writeSnapshots,
    int snapshotEventCountThreshold,
    Counter counterSnapshotRead,
    Counter counterSnapshotWrite
) { }
```

The `liveModel()` method passes snapshot config to `ProjectLiveModelCommand`.

#### 2d. Modify `ProjectLiveModelCommand` — the core change

**File:** `module/readmodels/ProjectLiveModelCommand.java`

This is the equivalent of `AggregateContextImpl` + `AggregateModule.aggregate()` for live models. The `execute()` method changes from:

```java
// BEFORE:
readModel = instantiate(readModelClass, constructorParams);
Projector.from(eventSource).towards(readModel).build().run();
```

To:

```java
// AFTER:
readModel = instantiate(readModelClass, constructorParams);

EventReference lastEventReference = null;

// 1. LOAD snapshot (if configured and read model implements SnapshotCapable)
if (readSnapshots && readModel instanceof SnapshotCapable<?> snapshotCapable) {
    String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
    String version = snapshotCapable.version();
    var loaded = snapshotStorage.load(key, version);
    if (loaded.isPresent()) {
        snapshotCapable.fromSnapshot(loaded.get().snapshot());
        lastEventReference = loaded.get().lastEventReference();
        counterSnapshotRead.increment();
    }
}

// 2. Replay remaining events from after snapshot
Projector<D> projector = Projector.from(eventSource)
    .towards(readModel)
    .startingAfter(lastEventReference)   // <-- key: resume from snapshot point
    .build();
ProjectorMetrics metrics = projector.run();

// 3. SAVE snapshot (if configured and threshold met)
if (writeSnapshots && readModel instanceof SnapshotCapable<?> snapshotCapable) {
    long totalEvents = (lastEventReference != null ? 1 : 0) + metrics.eventsStreamed();
    if (metrics.eventsStreamed() >= snapshotEventCountThreshold) {
        String key = snapshotCapable.key(readModel.readmodelName(), constructorParams);
        snapshotStorage.save(key, snapshotCapable.version(),
            snapshotCapable.takeSnapshot(), metrics.lastEventReference());
        counterSnapshotWrite.increment();
    }
}
```

The same logic applies to `ProjectLiveModelUnboundedCommand`.

#### 2e. Wire snapshot config through `BoundedContextBuilderImpl.build()`

**File:** `module/boundedcontext/BoundedContextBuilderImpl.java`

Change the `build()` method to pass `LiveModelSpecificationImpl` objects (not just classes) to `ReadModelModule`, so it has access to snapshot configuration:

```java
// BEFORE:
Collection<Class<?>> liveModelClasses = liveModelSpecs.stream()
    .map(LiveModelSpecificationImpl::readModelClass)
    .collect(...);
new ReadModelModule<>(..., liveModelClasses, ...);

// AFTER:
new ReadModelModule<>(..., liveModelSpecs, ...);
```

---

### 3. `sliceworkz-eventmodeling-testing` — no changes needed

The existing test infrastructure supports the pattern. Mock snapshot storages are test-local.

---

### 4. `sliceworkz-eventmodeling-tests-inmem` — new test class

#### New: `LiveModelSnapshotTest`

**File:** `module/readmodels/LiveModelSnapshotTest.java` (new)

Mirrors `AggregateCapabilityTest` for live models:

1. **`testLiveModelSnapshots()`** — Create a live read model that implements `SnapshotCapable`. Append N events, read the model. Verify snapshot is saved. Read again, verify snapshot is loaded and only delta events are replayed.

2. **`testLiveModelSnapshotsChangingVersion()`** — Same as aggregate test: changing version causes snapshot miss, full replay.

3. **`testLiveModelWithoutSnapshots()`** — Verify that live models without `SnapshotCapable` work exactly as before (no regression).

4. **`testLiveModelSnapshotReadOnly()`** / **`testLiveModelSnapshotWriteOnly()`** — Test the modes.

A mock read model + mock snapshot storage following the exact same pattern as `MockAggregate` + `MockSnapshotStorage` in the aggregate tests.

---

## Builder API Usage (end-user perspective)

### Without snapshots (unchanged):
```java
BoundedContext.newBuilder(...)
    .readmodel(AccountDetailsReadModel.class).live()
    .build();
```

### With snapshots (new):
```java
SnapshotStorage<AccountDetailsSnapshot> snapshotStorage = new InMemorySnapshotStorage<>();
// or: new PostgresSnapshotStorage<>(dataSource);
// or: any custom SnapshotStorage implementation

BoundedContext.newBuilder(...)
    .readmodel(AccountDetailsReadModel.class)
        .snapshots(snapshotStorage)
        .eventCountThreshold(200)
        .readAndWrite()                // returns to builder (terminal)
    .build();
```

### Read model implementation:
```java
public class AccountDetailsReadModel
    implements ReadModel<BankingDomainEvent>, SnapshotCapable<AccountDetailsSnapshot> {

    private DomainConceptId accountId;
    private AccountDetails account;

    public AccountDetailsReadModel(DomainConceptId accountId) {
        this.accountId = accountId;
    }

    // --- ReadModel methods (unchanged) ---
    @Override public EventQuery eventQuery() { ... }
    @Override public void when(BankingDomainEvent event) { ... }
    public Optional<AccountDetails> getAccountDetails() { ... }

    // --- SnapshotCapable methods (new) ---
    @Override
    public AccountDetailsSnapshot takeSnapshot() {
        return new AccountDetailsSnapshot(account);
    }

    @Override
    public void fromSnapshot(AccountDetailsSnapshot snapshot) {
        this.account = snapshot.accountDetails();
    }

    @Override
    public String version() { return "v1"; }

    // key() uses default implementation: "AccountDetailsReadModel/<accountId>"
}
```

## Snapshot Key Strategy

- Aggregates: `key(name, Tags)` → `"AggregateName/tagKey-tagValue/..."`
- Live models: `key(name, Object...)` → `"ReadModelName/param1.toString()/param2.toString()/..."`
- Both overridable by the user for custom key schemes
- Version matching identical: exact match required, mismatch = full replay

## Metrics (mirroring aggregates)

| Metric | Tags | Purpose |
|--------|------|---------|
| `sliceworkz.eventmodeling.readmodel.live.snapshot.read.count` | context, readmodel | Snapshot loads |
| `sliceworkz.eventmodeling.readmodel.live.snapshot.write.count` | context, readmodel | Snapshot saves |

Existing `sliceworkz.eventmodeling.readmodel.live.render` and `sliceworkz.eventmodeling.readmodel.live.duration` metrics remain unchanged.

## Files Changed Summary

| Module | File | Change |
|--------|------|--------|
| api | `snapshots/SnapshotCapable.java` | Add `key(String, Object...)` default method |
| api | `snapshots/LiveModelSnapshotSpecification.java` | **New** — builder spec interface |
| api | `readmodels/LiveModelSpecification.java` | Add `snapshots()` method |
| impl | `module/snapshots/LiveModelSnapshotSpecificationImpl.java` | **New** — mirrors `SnapshotSpecificationImpl` |
| impl | `module/boundedcontext/BoundedContextBuilderImpl.java` | Add snapshot fields to `LiveModelSpecificationImpl`, wire through `build()` |
| impl | `module/readmodels/ReadModelModule.java` | Hold `LiveModelInfo` instead of just classes, pass to commands |
| impl | `module/readmodels/ProjectLiveModelCommand.java` | Load/save snapshot around projection |
| impl | `module/readmodels/ProjectLiveModelUnboundedCommand.java` | Same as above |
| tests-inmem | `module/readmodels/LiveModelSnapshotTest.java` | **New** — test class mirroring `AggregateCapabilityTest` |

## What Stays Unchanged

- `SnapshotStorage<T>` interface — fully reused, zero changes
- `SnapshotSpecification` — aggregate-specific, untouched
- `SnapshotSpecificationImpl` — aggregate-specific, untouched (but `READ_AND_OR_WRITE` enum reused)
- `AggregateModule`, `AggregateContextImpl` — untouched
- Existing read model behavior without snapshots — fully backward compatible
- `BoundedContext.read()` / `readUnbounded()` — signature unchanged
