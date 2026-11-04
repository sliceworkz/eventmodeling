[![ci build - mvn package](https://github.com/sliceworkz/eventmodeling/actions/workflows/ci.yaml/badge.svg)](https://github.com/sliceworkz/eventmodeling/actions/workflows/ci.yaml)
[![Status: Experimental](https://img.shields.io/badge/status-experimental-red)](#project-status)
[![Maven Central](https://img.shields.io/maven-central/v/org.sliceworkz/sliceworkz-eventmodeling-bom)](https://central.sonatype.com/search?q=g:org.sliceworkz)
[![Quickstart](https://img.shields.io/badge/Event%20Modeling%20Quickstart%20Guide-0b5fff)](https://sliceworkz.github.io/posts/eventmodeling-quickstart/)
[![License: LGPL-3.0](https://img.shields.io/badge/license-LGPL--3.0-green)](LICENSE)

# Sliceworkz Event Modeling

**A Java framework that turns an [Event Model](https://eventmodeling.org) into running code, one vertical
slice at a time — event-sourced, with consistency boundaries per decision
([DCB](https://dcb.events/specification/)), on the [Sliceworkz EventStore](https://github.com/sliceworkz/eventstore).**

An Event Model describes a system as a timeline of slices: a user triggers a *command* that records an
*event*, *read models* show what happened, *automations* react to it, *translations* take in what other
systems say. This framework gives each of those a type, and each slice in the model a package in the code.
A slice holds everything it needs — its command, the decision models it decides on, its read model, its
endpoint — and nothing another slice can reach into. The framework does the wiring, the projections, the
background processing, the retries and the bookkeeping, so a slice stays a few small classes.

```java
@FeatureSlice(chapter = "Accounts")
public class FreezeAccountFeatureSlice implements Slice<Banking> {

    @Override
    public void configureCommand ( BoundedContextBuilder<Banking> builder ) {
        builder.command(FreezeAccountCommand.class);
    }
}
```

## The patterns, and what they are in code

| Event Modeling pattern | flow | what you write |
|---|---|---|
| **State change** | trigger → command → event | a `Command` deciding on `DecisionModel`s and raising events |
| **State view** | events → read model → UI / API | a `ReadModel`, projected live per read or in the background |
| **Automation** | events → to-do list → processor → command → event | a `TodoListReadModel` and an `Automation` working through it |
| | *or as a policy:* event → command | a `Policy`: whenever this event happens, issue that command |
| **Translation** | external event → command → event | a `Translator` turning inbound events into domain events |
| *Telling the world* | domain event → integration event → external system | a `Publisher` mapping it, a `Dispatcher` sending it (the outbox) |

Each slice registers its parts on the bounded context; the slice's type (state change, state view,
automation, translation) is derived from what it registers, never declared.


# A slice, start to finish

From the [banking example](sliceworkz-eventmodeling-examples/src/main/java/org/sliceworkz/eventmodeling/examples/banking),
lightly abridged.

**The events and entities** are records under sealed interfaces, declared once for the bounded context:

```java
public interface BankingDomain {
    record AccountId  ( String value ) implements EntityId { }
    Entity<AccountId> ACCOUNT = Entity.of("account", AccountId::new);   // the tag key, and the id factory

    sealed interface BankingEvent {
        record AccountOpened ( AccountId accountId, CustomerId customerId, LocalDate date ) implements BankingEvent { }
        record AccountFrozen ( AccountId accountId, String reason ) implements BankingEvent { }
        record AccountClosed ( AccountId accountId, LocalDate date ) implements BankingEvent { }
    }
}
```

**A decision model** reads exactly the facts a decision needs — here, one account's standing:

```java
public class AccountStandingDecisionModel implements DecisionModel<BankingEvent> {

    private final AccountId accountId;
    private boolean opened, frozen, closed;

    public AccountStandingDecisionModel ( AccountId accountId ) { this.accountId = accountId; }

    @Override public EventQuery eventQuery ( ) {
        return EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class, AccountFrozen.class, AccountClosed.class),
                                    ACCOUNT.tags(accountId));
    }

    @Override public void when ( Event<BankingEvent> event ) {
        switch ( event.data() ) {
            case AccountOpened ignored -> opened = true;
            case AccountFrozen ignored -> frozen = true;
            case AccountClosed ignored -> closed = true;
            default -> { }
        }
    }

    public boolean opened ( ) { return opened; }
    public boolean frozen ( ) { return frozen; }
    public boolean closed ( ) { return closed; }
}
```

**The command** decides on it and raises the event:

```java
public record FreezeAccountCommand ( AccountId accountId, String reason ) implements Command<BankingEvent> {

    @Override
    public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
        var account = new AccountStandingDecisionModel(accountId);
        var result = context.decisionModels(account);

        BusinessException.when(!account.opened(), "Account " + accountId.value() + " does not exist");
        BusinessException.when(account.closed(),  "Account " + accountId.value() + " is closed");
        BusinessException.when(account.frozen(),  "Account " + accountId.value() + " is already frozen");

        result.raiseEvent(new AccountFrozen(accountId, reason.strip()), ACCOUNT.tags(accountId));
    }
}
```

The framework pins the consistency boundary before the decision model is read, and appends
`AccountFrozen` only if no event about that account landed in the meantime. A conflict is an
`OptimisticLockingException`; `executeWithRetry(...)` re-reads and re-decides for you.

**A policy** in another slice reacts to it — whenever an account is frozen or closed, block its cards:

```java
public class BlockCardsOfAccountPolicy implements Policy<BankingEvent> {

    @Override public EventQuery eventQuery ( ) {
        return EventQuery.forTypes(AccountFrozen.class, AccountClosed.class);
    }

    @Override public Optional<Command<BankingEvent>> react ( Event<BankingEvent> event ) {
        return switch ( event.data() ) {
            case AccountFrozen f -> Optional.of(new BlockCardsOfAccountCommand(f.accountId(), "account frozen: " + f.reason()));
            case AccountClosed c -> Optional.of(new BlockCardsOfAccountCommand(c.accountId(), "account closed"));
            default -> Optional.empty();
        };
    }
}

// in its slice:  builder.command(BlockCardsOfAccountCommand.class)
//                       .policy(new BlockCardsOfAccountPolicy()).fromNowOn().stallOnRejection();
```

**The bounded context** finds every slice in a package, and the application drives it:

```java
public interface Banking extends BoundedContext<BankingEvent, BankingInboundEvent, BankingOutboundEvent> { }

Banking banking = BoundedContext.newBuilder(Banking.class)
    .name("banking")
    .eventStorage(PostgresEventStorage.newBuilder().build())    // or InMemoryEventStorage for tests
    .features()
        .rootPackage(BankingDomain.class.getPackage())
        .done()
    .build();
banking.start();

banking.executeWithRetry(new FreezeAccountCommand(accountId, "suspected fraud"));
AccountDetailsReadModel details = banking.read(AccountDetailsReadModel.class, accountId);
```

**And a test** states the slice's behaviour in the terms of the model:

```java
@Test
void anAccountIsFrozenOnce ( ) {
    given()
        .event(opened(), ACCOUNT.tags(ACCOUNT_1))
        .event(new AccountFrozen(ACCOUNT_1, "suspected fraud"), ACCOUNT.tags(ACCOUNT_1))
        .when(new FreezeAccountCommand(ACCOUNT_1, "again"))
        .then()
        .businessError("Account acc-1 is already frozen");
}
```


# What the framework takes care of

**Consistency.** Every command is a DCB decision: its decision models are read as of one pinned moment,
and its events are appended only if none of the facts it read changed. Uniqueness, quotas and invariants
across several entities need no aggregate boundary to be designed up front. Idempotency keys make a
retried command store nothing twice.

**Read models, from cheap to scalable.** A live read model is projected on the spot and always current.
When that gets expensive, bound the replay, project it in the background in memory, keep it durable in SQL
(rows and position committed together, so a crash never double-counts), seed a live read from a background
projection, or snapshot. [Choosing a read model](CHOOSING-A-READ-MODEL.md) walks the ladder.

**Background work that keeps going.** Automations, policies, background read models, translators,
publishers and dispatchers each run on their own processor with a bookmark: at-least-once, resumable after
a restart, retried with backoff on a transient failure, and failures contained per item rather than
stopping the work. Across several instances, leader election runs each one on exactly one of them.

**Business rules people may break.** A rule carries an SBVR enforcement level — strictly enforced,
overridable with an explanation, pre-authorized, post-justified, deferred, or a guideline. The command
checks it, the framework judges it, the violation is recorded on the event, and `evaluate(...)` previews
the outcome for a front end before anything is stored. See [Business rules](BUSINESS-RULES.md).

**Talking to the outside.** Ports and adapters for what a slice calls, monitored per call; translators
for what comes in; publishers and dispatchers for what goes out, with every outbound event traceable to
the domain event it was published for.

**Personal data.** A `Shreddable<T>` field is encrypted per person; `erase(...)` destroys the key and the
event log stays untouched.

**Operations.** Every command, projection, automation run and port call is reported as a
`BoundedContextEvent`, which a monitoring stream or dashboard can follow, and
to an observer SPI you bind to your metrics or tracing library. Correlation ids follow one flow through
commands, automations and contexts. Health checks back liveness and readiness probes, and an operator can
stop and restart processors remotely through the event store.

**A narrow surface per caller.** A built context can do everything; a web controller only needs to execute
commands and read read models. Hand it `ApplicationCapabilities` instead of the context, and erasure,
shutdown or stopping an automation no longer compile there. See [Who may do what](WHO-MAY-DO-WHAT.md).


# Getting started

## Requirements

- **Java 21** or later at runtime
- An event storage from the [Sliceworkz EventStore](https://github.com/sliceworkz/eventstore):
  PostgreSQL 16+ in production, in-memory for development and tests
- To build from source: JDK 25 and Maven 3.6.3+ (the build still targets release 21)

## Add the dependency

The BOM pins the framework and the matching eventstore version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.sliceworkz</groupId>
            <artifactId>sliceworkz-eventmodeling-bom</artifactId>
            <version>0.8.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventmodeling-impl</artifactId>
    </dependency>
    <!-- an event storage; sliceworkz-eventstore-infra-postgres in production -->
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventstore-infra-inmem</artifactId>
    </dependency>
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventmodeling-testing</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Run the examples

```bash
mvn install -DskipTests
cd sliceworkz-eventmodeling-examples
mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.banking.BankingExample"
```

| example | shows |
|---|---|
| `banking.BankingExample` | a bounded context, commands, a command returning a result, live and background read models |
| `banking.BankingClosingTheBooksExample` | keeping reads cheap on a long history: closing the books per month, and a seeded read model |
| `banking.BankingBusinessRulesExample` | business rules at every enforcement level, overrides, and previews |
| `payments.PaymentsExample` | an automation calling an external gateway through a port, and every way that call can fail |

The [quickstart guide](https://sliceworkz.github.io/posts/eventmodeling-quickstart/) builds an application
step by step.


# Testing

`sliceworkz-eventmodeling-testing` has a `given / when / then` base class for each kind of part —
`CommandTest`, `LiveModelTest`, `AggregateTest`, `AutomationTest`, `PolicyTest`, `TranslatorTest`,
`PublisherTest`, `DispatcherTest` and `SqlReadModelTest`. They run the same code a deployment runs,
synchronously and without sleeps: an automation test runs the production batch loop, and a redelivered
item is a one-line test. A test annotated `@Test` runs in memory; one annotated `@ForEachBackend` runs
against every event storage you register, PostgreSQL included. See the
[testing module](sliceworkz-eventmodeling-testing/README.md).


# Guides

- [Choosing a read model](CHOOSING-A-READ-MODEL.md) — which kind of read model to reach for, in what
  order, and what each step costs; [the read model manual](READ-MODEL-MANUAL.md) walks the same ladder as
  a tutorial.
- [Where a validation goes](WHERE-VALIDATIONS-GO.md) — value objects, input checks, decision models,
  uniqueness and the edges, sorted by one rule: a validation runs before events exist, or never.
- [Business rules and enforcement levels](BUSINESS-RULES.md) — rules people may break under conditions,
  who may override them, what gets recorded, and how a user sees it before submitting.
- [Who may do what](WHO-MAY-DO-WHAT.md) — the audiences a bounded context's capabilities are filed under,
  and how each caller holds only its own.
- [`CLAUDE.md`](CLAUDE.md) — the design record: why each mechanism is what it is, and the alternatives
  that were rejected.


# Modules

| artifact | what it is |
|---|---|
| `sliceworkz-eventmodeling-api` | The interfaces you code against: bounded context, commands, read models, automations, policies, translators, publishers, dispatchers, business rules |
| `sliceworkz-eventmodeling-impl` | The implementation, found through `ServiceLoader` |
| `sliceworkz-eventmodeling-testing` | The test base classes |
| `sliceworkz-eventmodeling-bom` | Bill of materials, importing the eventstore's too |

Every jar declares an `Automatic-Module-Name` equal to its root package (`org.sliceworkz.eventmodeling`, …).


# Project status

Experimental. The framework is moving quickly and its API changes between minor versions; every change is
listed in the [release notes](https://github.com/sliceworkz/eventmodeling/releases). The event store
underneath is in beta.

## Supply chain and security

Every release ships a CycloneDX SBOM — per module on Maven Central (`cyclonedx` classifier) and aggregated
on the [GitHub release](https://github.com/sliceworkz/eventmodeling/releases) — attested to the jars it
describes:

```bash
gh attestation verify sliceworkz-eventmodeling-api-<version>.jar --repo sliceworkz/eventmodeling --predicate-type https://cyclonedx.org/bom
```

Every build scans for known vulnerabilities, a release with a fixable High or Critical one is stopped, and
released versions are re-scanned daily. [SUPPLY-CHAIN.md](SUPPLY-CHAIN.md) explains how it fits together;
[SECURITY-RELEASES.md](SECURITY-RELEASES.md) is the procedure when a scan fails. Report vulnerabilities
privately, as described in [SECURITY.md](SECURITY.md).

## Contributing and license

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under the
[LGPL-3.0](LICENSE); third-party components are listed in [NOTICE](NOTICE).
