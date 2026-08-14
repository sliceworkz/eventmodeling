# sliceworkz-eventmodeling-testing

Base classes for testing an application built on the framework: `CommandTest`, `AggregateTest`,
`LiveModelTest`, `AutomationTest`, `TranslatorTest`, `DispatcherTest` and `SqlReadModelTest`.

```xml
<dependency>
    <groupId>org.sliceworkz</groupId>
    <artifactId>sliceworkz-eventmodeling-testing</artifactId>
    <scope>test</scope>
</dependency>
```

## Running against every event storage

`CommandTest`, `AggregateTest`, `LiveModelTest`, `AutomationTest`, `TranslatorTest` and
`DispatcherTest` all extend `AbstractBoundedContextTest`, which
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

## Testing an automation

`AutomationTest` tests an `Automation` together with its `TodoListReadModel` — synchronously, on the
test thread, with no polling and no waits. The batch loop it runs is literally the production one
(`AutomationBatch`, shared with `AutomationProcessor`), over a real `AutomationContext` on a real
bounded context, so `publishAndRecord`, idempotency keys and `onFailure` behave exactly as deployed.

```java
class ExecutePaymentAutomationTest extends AutomationTest<PaymentToExecute, PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Override
    public Automation<PaymentToExecute, PaymentsDomainEvent, PaymentsOutboundEvent> automation ( ) {
        return new ExecutePaymentAutomation(new PaymentsToExecuteTodoList(), gateway);
    }

    @Test
    void aPaymentIsExecutedAndLeavesTheTodoList ( ) {
        given(new PaymentRequested(paymentId, iban, 100_00))
            .expectTodoItems(new PaymentToExecute(paymentId, iban, 100_00, 0, null))
            .whenBatchRuns()
            .itemsHandled(1)
            .events(new PaymentExecuted(paymentId, gatewayReference))
            .and()
            .expectNoTodoItems();
    }
}
```

How a round works, and why it matches production: `whenBatchRuns()` first projects everything
appended so far into the todo list (what the todo list's own projector does on its thread in
production), then runs exactly **one** batch — and deliberately does **not** project afterwards, so a
batch's events reach the todo list at the start of the *next* round, exactly as deployed. That is
what makes at-least-once delivery expressible:

- `whenItemsAreRedelivered()` is the same round *without* the catch-up, so the todo list re-offers
  the items the previous batch already handled — the crash-between-append-and-bookmark case.
  `.whenItemsAreRedelivered().noEvents()` is the one-line proof that item-derived idempotency keys
  make the repeat a no-op.
- `CONTINUE_AND_RETRY_ITEM_LATER` needs no harness support: a failed item returns on the next
  `whenBatchRuns()` because the todo list still projects it — unless `onFailure` recorded an event
  that defers or drops it, which the next round's projection applies.
- A batch that ends in `STOP_AUTOMATION` (assert with `.automationStopped()`) makes further batches
  refuse to run until `restartAutomation()` — as in production, where a stopped automation waits for
  an operator. Restarting without fixing the cause finds the same item at the head and stops again.
- Don't wait out retry delays — seed them. A scenario like "declined three times, then abandoned"
  seeds the `PaymentAttemptFailed` events a past run would have recorded, with a due time already
  reached, and runs one batch on top.

**Never register the automation or its todo list on the builder** (via `configure`). The harness owns
both instances and drives them itself; a registered automation runs on a real processor whose thread
races the synchronous rounds, and every assertion turns non-deterministic. What the harness leaves to
the framework's own tests is the processor around the loop: leader election, the catch-up guard,
backoff pacing, `AutomationStatus`.

The worked example to copy is `ExecutePaymentAutomationTest` (and `PaymentsToExecuteTodoListTest` for
the projection alone) in `sliceworkz-eventmodeling-examples`.

## Testing a translator

`TranslatorTest` registers the translators under test and runs them through the *interactive* path —
`BoundedContext.translate(...)`, synchronous, in the calling thread:

```java
class OrderRegisteredTranslatorTest extends TranslatorTest<ShopEvent, PartnerEvent, Void> {

    @Override
    public List<Translator<PartnerEvent, ShopEvent>> translators ( ) {
        return List.of(new OrderRegisteredTranslator());
    }

    @Test
    void aPartnerOrderBecomesADomainOrder ( ) {
        given().when(new PartnerOrderPlaced("o1", 3))
               .then().event(new OrderReceived("o1", 3));
    }

    @Test
    void anUnknownPartnerEventIsLoud ( ) {
        given().when(new PartnerPing())
               .then().noTranslatorRegistered();
    }
}
```

Two properties of that path are asserted for free on every test: the inbound event is **not**
persisted (that is `translate()`'s contract), and an inbound event no translator claims throws
`NoTranslatorRegisteredException` rather than vanishing. One caveat: the interactive path matches
translators on the inbound event's *type* only, so a translator whose `eventQuery()` also requires
tags never matches interactively. The asynchronous path — `incoming(...)`, projector-driven — is
framework behaviour and stays with the framework's own integration tests; the translation logic is
identical on both paths.

## Testing a dispatcher

`DispatcherTest` drives a `Dispatcher` as what it is — a projection over the outbound stream — and
the test asserts on the fake external system it publishes to:

```java
class AnnouncePaymentDispatcherTest extends DispatcherTest<PaymentsDomainEvent, Void, PaymentsOutboundEvent> {

    private final RecordingMessageBus bus = new RecordingMessageBus();

    @Override
    public Dispatcher<PaymentsOutboundEvent> dispatcher ( ) {
        return new AnnouncePaymentDispatcher(bus);
    }

    @Test
    void anAnnouncementReachesTheBusExactlyOnce ( ) {
        given(new PaymentAnnounced("p1"))
            .whenDispatched().delivered(1)
            .and()
            .whenDispatched().nothingDelivered();   // the cursor plays the bookmark
        assertEquals(List.of("p1"), bus.messages());
    }
}
```

Seeding: `given(...)` appends raw outbound events as fixture data (the idempotency-key requirement
lives in the command path, not in storage, so this is legitimate); `givenExecuted(command, key)` is
the faithful alternative — a real `OutboundCommand` through the bounded context under an externally
provided key, exactly as an automation's `publishAndRecord` does, so executing it twice under the
same key appends once.

Redelivery is first-class, because a dispatcher is where duplicate publishing costs most: an absent
bookmark means "publish everything again". `whenDispatched()` keeps one projector across rounds (a
second round delivers only what is new); `whenRedeliveredFromTheStart()` builds a fresh projector
from zero — the lost-bookmark or renamed-dispatcher case — and the test then faces up to what its
dispatcher does with duplicates. As with the automation base, **never register the dispatcher on the
builder**; the end-to-end delivery path through a registered dispatcher is the framework's own
`DispatcherDeliveryTest`.

## SQL read models

`SqlReadModelTest` is about a different database — the one a `SqlReadModelProjector` writes its rows
into, not the event storage — and it has always covered both H2 and PostgreSQL through a `@Nested`
class per database. `postgresDataSource(String image)` picks the server version where that matters.

## What a test gets

Beyond the fluent `given/when/then` of each base class, `AbstractBoundedContextTest` exposes:

- `kernel()` — the bounded context under test
- `eventStore()` / `eventStorage()` — the store the context was built over, for asserting on what
  was actually written
- `eventStreamId()` / `inboundEventStreamId()` / `outboundEventStreamId()` — the three streams of
  that context, and `domainStream()` / `inboundStream()` / `outboundStream()` as ready-made handles
  over them
- `backend()` — the backend supplying this invocation's storage, under `@ForEachBackend`
- `waitBecauseOfEventualConsistency(...)` — for assertions on anything projected asynchronously
