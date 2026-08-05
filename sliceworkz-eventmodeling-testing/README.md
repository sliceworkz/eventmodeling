# sliceworkz-eventmodeling-testing

Base classes for testing an application built on the framework: `CommandTest`, `AggregateTest`,
`LiveModelTest` and `SqlReadModelTest`.

```xml
<dependency>
    <groupId>org.sliceworkz</groupId>
    <artifactId>sliceworkz-eventmodeling-testing</artifactId>
    <scope>test</scope>
</dependency>
```

## Running against every event storage

`CommandTest`, `AggregateTest` and `LiveModelTest` all extend `AbstractBoundedContextTest`, which
builds a bounded context over a storage and releases both around every test method. **Which storage
that is depends on how the test method is annotated** — the same choice the framework's own suite
makes:

| annotation | runs |
|---|---|
| `@Test` | once, against an in-memory store. Nothing to configure |
| `@ForEachBackend` | once per registered `EventStoreBackend`, each reported under the backend that produced it (`openingAnAccount [postgres:18]`) |

```java
class OpenAccountCommandTest extends CommandTest<BankingEvent, Void, Void> {

    @Override public Class<BankingEvent> domainEventType ( ) { return BankingEvent.class; }
    @Override public Class<Void> inboundEventType ( )        { return Void.class; }
    @Override public Class<Void> outboundEventType ( )       { return Void.class; }

    @ForEachBackend                                     // in-memory, PostgreSQL 17, PostgreSQL 18, ...
    void openingAnAccount ( ) {
        given().when(new OpenAccountCommand("123"))
               .then().event(new AccountOpened("123"));
    }

    @Test                                               // in-memory only, and cheap
    void openingAnAccountTwiceIsRejected ( ) {
        given(new AccountOpened("123")).when(new OpenAccountCommand("123"))
                                       .then().error("account 123 already exists");
    }
}
```

Use `@ForEachBackend` where the scenario is worth proving against the storage the application will
actually run on — anything touching consistency boundaries, tags, idempotency keys or ordering. Use
`@Test` where the scenario is about your own code and no storage can change the answer; it costs no
container.

### Registering the backends

The backend set is data, not code. List the ones you want in

```
src/test/resources/META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend
```

```
org.sliceworkz.eventstore.testing.backend.InMemoryBackend
org.sliceworkz.eventstore.testing.backend.InMemoryFsBackend
org.sliceworkz.eventstore.testing.backend.Postgres17Backend
org.sliceworkz.eventstore.testing.backend.Postgres18Backend
```

`InMemoryBackend` needs nothing further — it comes with this module. The others need their storage
on the test classpath, which `sliceworkz-eventstore-testing` declares `<optional>` so that a project
using only the in-memory one does not inherit a database driver:

```xml
<dependency>
    <groupId>org.sliceworkz</groupId>
    <artifactId>sliceworkz-eventstore-infra-inmem-fs</artifactId>   <!-- for InMemoryFsBackend -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.sliceworkz</groupId>
    <artifactId>sliceworkz-eventstore-infra-postgres</artifactId>   <!-- for the Postgres backends -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.github.f4b6a3</groupId>
    <artifactId>uuid-creator</artifactId>                           <!-- PostgreSQL 17 and older -->
    <scope>test</scope>
</dependency>
```

The PostgreSQL backends are Testcontainers-managed, so they need a Docker daemon; the driver,
Testcontainers and HikariCP come with this module. One container is started per image per JVM and
shared, and per-test isolation is a schema drop and recreate rather than a fresh container.

Registering no backend at all is fine — every `@Test` keeps working. A `@ForEachBackend` method with
an empty registry fails with a message saying what to put in the service file.

### Narrowing a run

```bash
mvn test -Deventstore.testing.backends=inmem            # skip the containers entirely
mvn test -Deventstore.testing.backends=inmem,postgres:18
```

Handy locally and in a pre-commit hook; leave the full set to CI. The names are the backends' own
(`inmem`, `inmem-fs`, `postgres:16`, `postgres:17`, `postgres:18`).

### One thing to know when upgrading

`AbstractBoundedContextTest` used to build an `InMemoryEventStorage` of its own, which is why the
matrix was out of reach from here. It now extends the eventstore's `AbstractEventStoreTest`, whose
lifecycle hooks are `public void setUp()` and `public void tearDown()`. Existing tests need no
change — **unless** a subclass happens to declare a method of its own called `setUp` or `tearDown`
without `public`, which the compiler now rejects as weakening the inherited access. Either make it
`public` and call `super`, or give it a name of its own:

```java
@BeforeEach
public void setUp ( ) {          // was: void setUp ( )
    super.setUp();               // builds the storage and the bounded context
    ...
}
```

## SQL read models

`SqlReadModelTest` is about a different database — the one a `SqlReadModelProjector` writes its rows
into, not the event storage — and it has always covered both H2 and PostgreSQL through a `@Nested`
class per database. `postgresDataSource(String image)` picks the server version where that matters.

## What a test gets

Beyond the fluent `given/when/then` of each base class, `AbstractBoundedContextTest` exposes:

- `kernel()` — the bounded context under test
- `eventStore()` / `eventStorage()` — the store the context was built over, for asserting on what
  was actually written
- `eventStreamId()` — the domain stream of that context
- `backend()` — the backend supplying this invocation's storage, under `@ForEachBackend`
- `waitBecauseOfEventualConsistency(...)` — for assertions on anything projected asynchronously
