# Who may do what with a bounded context

A built bounded context can do everything: execute commands, read read models, append a domain event
no command raised, translate inbound events, stop an automation, restart a projector, erase a
person's data, hand out a port, and terminate itself. It has to — the code that built it needs all of
it.

Almost nothing else does. That is the rule this page hangs on: **what a reference may do should say
who is holding it.** A web controller holding `Banking` can call `erase()`, `stopAutomation()` and
`event()`; none of those is a mistake the compiler can see, and none of them is a mistake anybody
makes on purpose. Narrowing the reference makes them mistakes the compiler *does* see, and it costs a
type name.

This is a boundary of discipline, not of security. The object behind a narrowed reference is still
the whole context, and a cast reaches it. What it buys is that reaching past the boundary has to be
written down, so it shows up in review instead of in an incident.

---

## The audiences

Every capability a bounded context carries is filed under one of these. `AllCapabilities` — which
`BoundedContext` extends — is their composition and nothing more.

| audience | interface | what it carries |
|---|---|---|
| **application** — a controller, a job, any adapter driving the domain | `ApplicationCapabilities<D,O>` | `execute`, `executeWithRetry`, `read`, `aggregate` |
| **inbound edge** — a webhook, a consumer feeding the domain | `TranslationCapability<I>` | `incoming`, `translate` |
| **operator** — an admin endpoint, a dashboard | `OperationsCapabilities` | `automations`, `restartAutomation`, `stopAutomation`, `processors`, `restartProcessor`, `stopProcessor` |
| **erasure requests** | `PrivacyCapability` | `erase`, `eraseCategory` |
| **the escape hatch** | `ProvidedEventCapability<D>` | `event` |
| **owner** — whatever built the context | `BoundedContext<D,I,O>` | all of the above, plus `start`/`stop`/`terminate`, `port`, the slice inventory |

`UnboundedReadModelCapability<D>` sits outside this table: it reads across every stream regardless of
context boundaries, is not part of `AllCapabilities`, and is reached by naming it on a context
interface of your own. See its javadoc.

## Narrowing costs a reference type

A built context already *is* each of these, so there is nothing to convert and nothing to wrap:

```java
Banking banking = BoundedContext.newBuilder(Banking.class) /* ... */ .build();
banking.start();                                              // the owner, holding everything

ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent> app = banking;
app.execute(new OpenAccountCommand(customerId));              // fine
app.read(AccountDetailsReadModel.class, accountId);           // fine
app.terminate();                                              // does not compile
app.erase("customer", id, reason);                            // does not compile
```

Build the context in one place and hand out the narrow types from there, so a caller is given the
reach its job needs and cannot be handed another's by a wiring change nobody looked at twice.

## An alias interface, for the same reason `Banking` exists

`ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent>` at every call site is the same
noise `BoundedContext<BankingDomainEvent, BankingInboundEvent, BankingOutboundEvent>` would be, which
is why the context interface exists at all. Give each audience the same treatment:

```java
public interface BankingApi extends ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent> { }

public interface Banking extends BoundedContext<BankingDomainEvent, BankingInboundEvent, BankingOutboundEvent>,
                                 BankingApi { }
```

`Banking` reaches `ApplicationCapabilities` twice over — through `BoundedContext` and through
`BankingApi` — with the same type arguments both ways, which is legal and is what makes a mistyped
alias a compile error rather than a second surface. Controllers then take `BankingApi`.

Nothing requires the alias: `Banking` is an `ApplicationCapabilities<BankingDomainEvent,
BankingOutboundEvent>` whether or not you declare one.

## Why each line of the table falls where it does

**`event()` is not an application capability.** It appends a domain event that no command raised: no
decision model is read, no consistency boundary is pinned, and none is checked on append, so no
`OptimisticLockingException` is possible. It exists for facts that are already settled by the time
they reach the context — a CRUD front-end recording what it has stored elsewhere, an import, a
migration — and for automations and translators, which reach it through their own context. On a
bounded context it is opted into by naming `ProvidedEventCapability`, so an application that reaches
for it has said so. Give what you append an idempotency key derived from the fact, never from the
attempt.

**Lifecycle belongs to the owner.** `start()`, `stop()` and `terminate()` are the other end of
`build()`: `terminate()` closes the `EventStore` the context built and drains its processor threads,
and whoever built the context is the only caller with a reason to. An operator stops a named
automation or processor instead, which is what `OperationsCapabilities` carries.

**An operator decides nothing.** `OperationsCapabilities` has no `read` and no `execute`: an
operations tool that can also execute commands is one that can make business decisions under an
operator's credentials. Both halves address *the instance they are called on* — reaching another
instance is the management stream's job, which ends up calling exactly these methods on the instances
an instruction names.

**An erasure is its own audience.** `erase()` answers a request from a person, under an authority
neither a controller nor an operations dashboard carries, and it is irreversible — it destroys keys,
and every value sealed under them becomes unreadable in the events table, the write-ahead log, the
replicas and every backup. It stays a leaf so that holding it is a decision.

**Aggregates ride with commands.** `aggregate(...)` is a second write path, and an `Aggregate` raises
through its own `AggregateContext` — but it raises *within an identity*, against a boundary, which is
what `event()` does not. Both checked write paths therefore sit on the application surface, and a
team using one of the two simply never registers the other.

**The inbound edge is separate from the application surface.** `incoming` and `translate` feed the
domain events from outside, which is a different job from deciding on behalf of a user. An adapter
that genuinely does both holds both types; most hold one.

## Adding a capability

Decide whose it is. Put it in an audience interface, or add an audience for it and give it a row in
the table above. `CapabilitySurfaceTest` fails a capability reachable on a bounded context and on no
narrower reference, and `CapabilityAudienceTest` compiles probes against each audience to pin what it
accepts and what it refuses — because a capability that quietly widens a surface breaks no test on
its own: the extra method is simply never called through the narrow type.
