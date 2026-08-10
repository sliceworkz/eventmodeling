# Where a validation goes

Every validation answers one of three questions: *can this data be said at all* (structure), *is this
request well-formed* (input), and *does history permit it* (invariants). The framework has one place
for each, and most validation bugs are a check written in the wrong one — usually a check that runs
against events that already exist.

That is the rule the whole page hangs on: **a validation runs before events exist, or never.** The
write path validates; the read path accepts. An event that was appended is a fact, and code that reads
facts — a payload record's constructor, an upcaster, a projection, a translator — is not being asked
for an opinion. A rule enforced on the read path does not reject bad data, because the data is already
there; it rejects *your ability to read it*, which is strictly worse.

Each step below runs later than the one before it and can guard something the earlier ones cannot.
This page is the decision guide, a sibling of [CHOOSING-A-READ-MODEL.md](CHOOSING-A-READ-MODEL.md);
where a step touches read models, it links there rather than restating it.

---

## 1. Value objects — validity that travels with the data

```java
// carried inside event payloads → the canonical constructor must accept anything ever stored
public record EmailAddress ( String value ) {
    public static EmailAddress of ( String raw ) {
        if ( raw == null || !raw.contains("@") ) { throw new IllegalArgumentException("not an email: " + raw); }
        return new EmailAddress(raw.strip().toLowerCase());
    }
}

// never persisted (a read model's answer, a command's parameter) → validate as hard as you like
public record AccountDetails ( String accountId, String customerId, LocalDate openDate ) {
    public AccountDetails {
        if ( accountId == null || customerId == null || openDate == null ) {
            throw new IllegalArgumentException("all fields are required");
        }
    }
}
```

A record with a validating static factory gives you the guarantee you want from a value object — an
instance implies the rules held — and it is where *normalisation* belongs too (trim, lowercase), so
that equality and tag matching see one spelling. `DomainConceptId.of(...)` in the API is the in-tree
shape.

**The split matters: where the record is carried inside an event payload, the canonical constructor
must stay lenient.** Jackson reconstructs payload records *through the canonical constructor* on every
read of history. A compact constructor that throws turns a tightened rule into a poison event: the
first legacy event that fails the new rule becomes an `EventDeserializationException` — never
retryable, surfaced from whatever read touches it, and through a `Projector` it stops the read model
cold. Nothing fails when you add the rule; it fails months later, on data that was valid when written.
The eventstore's own `Tag` states the principle in its javadoc: the constructor rejects, but `parse` —
the read path — deliberately stays lenient, "because it has to keep reading tags written before this
was enforced".

For a record nothing ever persists, the strict compact constructor is fine and better — take the hard
guarantee where it costs nothing.

*Costs:* with the lenient constructor, an instance only implies validity when it came from the
factory. `AbstractBoundedContextArchUnitTest.valueObjectsShouldBeCreatedViaFactoryMethods()` closes
that gap — a record exposing a static factory must not have its constructor called from outside — and
extending that test class is how an application inherits the rule.

## 2. Reject the malformed request — before any history is read

```java
public OpenAccountCommand ( DomainConceptId customerId, BigDecimal initialDeposit ) {
    if ( customerId == null )                              { throw new IllegalArgumentException("customerId is required"); }
    if ( initialDeposit.signum() < 0 )                     { throw new IllegalArgumentException("initial deposit cannot be negative"); }
    this.customerId = customerId;
    this.initialDeposit = initialDeposit;
}
```

Input validation is about the request, not the world: no event history can make a null account id
acceptable. It belongs in the command's constructor (or the top of `execute`, before
`decisionModels(...)`), so a malformed request costs nothing — no projection, no append, no
`CommandFailed` that anyone needs to alert on. `IllegalArgumentException` is the right type: the
caller sent garbage, and retrying the identical request can never succeed.

If the check needs a value object anyway, step 1 already did the work — a constructor taking
`EmailAddress` instead of `String` cannot receive a malformed email.

## 3. Business rules — decide on a decision model, let the append re-check

```java
@Override
public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {

    var period = new ActivePeriodDecisionModel(accountId);
    var result = context.decisionModels(period);          // projects the model AND pins the boundary

    BusinessException.when(!period.accountExists(),                 "Account does not exist");
    BusinessException.when(period.isPeriodClosed(),                 "Period " + period.activeMonth() + " is closed");
    BusinessException.when(period.balance().compareTo(amount) < 0,  "Insufficient balance");

    result.raiseEvent(new MoneyWithdrawn(accountId, period.activeMonth(), amount, description), tags);
}
```

This is where invariants live, and the shape is always the same three lines: **select decision models,
check, raise.** A `DecisionModel` is a projection the command instantiates inline — `eventQuery()`
scoped by tags to the entity, `when(...)` folding state, accessors for the checks
(`ActivePeriodDecisionModel` in the banking example is the one to copy, including the savepoint
`initQuery()` that bounds its replay).

What makes this more than a lookup: `context.decisionModels(...)` puts the events the model was
projected from **inside the command's consistency boundary**. The optimistic-lock filter is the union
of the queries the models were *actually read with*, and it travels with the append — so the DCB
check at append time re-asks exactly the question the command decided on. Three distinct failures come
out of this step, and they want different responses:

| | thrown by | means | retry? |
|---|---|---|---|
| `IllegalArgumentException` | the command, before any read | the request is malformed | never |
| `BusinessException` | the command, after projecting | history says no | never — the answer *is* no |
| `OptimisticLockingException` | the append | new relevant facts since the decision | yes — re-execute, which re-projects and re-decides |

All three reach the caller; the kernel additionally emits `CommandFailed` (or
`CommandFailedOnOptimisticLocking`) for observers, so a rejected command is visible without being
anybody else's failure. The examples predate `BusinessException` and throw `IllegalStateException` —
the API ships `BusinessException` (with the `when(condition, message)` helper) precisely so a rule
rejection is distinguishable from a bug in a catch block and in the observability record; prefer it.

**A rule that needs no history needs no decision model** — but say so: a command must call
`decisionModels(...)` or `noDecisionModels()`, and `build()`-style silence is not an option
(`getCommandResult()` rejects a command that called neither). `noDecisionModels()` is the greppable
declaration that this command's append needs no guard. An `OutboundCommand` is not even offered
decision models — they cannot guard an append to the outbound stream, and a boundary that guards
nothing, silently, is worse than none; its correctness comes from idempotency keys (step 5).

*Costs:* one projection per execution, bounded the same way a live read model is — by tags and a
savepoint (`initQuery()`), per the read-model ladder's step 2.

## 4. Uniqueness — the boundary you expect to be empty

```java
class EmailNotTakenDecisionModel implements DecisionModel<CrmEvent> {
    private final EmailAddress email;
    private boolean taken = false;

    public EventQuery eventQuery ( ) {
        return EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class),
                                    Tags.of("email", email.value()));
    }
    public void when ( Event<CrmEvent> e ) { taken = true; }
    public boolean taken ( ) { return taken; }
}

// in RegisterCustomerCommand.execute:
var claim = new EmailNotTakenDecisionModel(email);
var result = context.decisionModels(claim);
BusinessException.when(claim.taken(), "email already registered: " + email.value());
result.raiseEvent(new CustomerRegistered(customerId, email), Tags.of("email", email.value()));
```

"No two customers with this email" is a rule about a *set*, not an entity, and the reflex is to check
a read model — which is a race (see Shapes to avoid). The event-sourced answer is a consistency
boundary you expect to be empty: a decision model whose query matches the claim, reading **nothing**.
An empty read is still a boundary — the expected reference is empty under a real filter, and the
append is admitted only if *still* nothing matches. Two concurrent registrations of the same email:
exactly one wins, the other gets `OptimisticLockingException`, guaranteed per storage backend by the
eventstore TCK's `ConcurrentOptimisticLockingTest`. The `taken()` check in the command gives the
ordinary sequential case its `BusinessException`; the boundary is what makes the concurrent case safe.

Two things make the tag reliable as the uniqueness key. Matching is **exact** — no prefix, no
wildcard, no case folding — so the value must be normalised to one spelling before it becomes a tag,
which is step 1's job (`EmailAddress.of` lowercases and strips; `Tag` rejects padded whitespace rather
than fixing it). And the claim must be *on the event and in the query*: an event raised without the
tag is invisible to the boundary that is supposed to defend it.

*Costs:* the claim events must exist forever (releasing a claim is another event the model folds), and
the boundary serialises concurrent appends for one email — which is exactly what you asked for.

## 5. The edges — accept what arrived, validate what you do about it

Everything above runs inside a command, before the append. At the edges of the context the posture
inverts: **what has arrived can only be accepted; what you do about it is where the validation goes.**

- **A translator must not throw.** Translators run behind a projector, and a projector has no failure
  containment: one throwable stops that translator's processor until the bounded context is started
  again — every inbound event behind the bad one waits, on one log line. An inbound event that fails
  validation is *decided about in-band*: ignore it (the `default -> { }` arm), or record the rejection
  as a domain event, which makes the dead-letter view an ordinary read model. (Through the synchronous
  `boundedContext.translate(...)` an exception propagates to the caller instead — that path may
  validate loudly, because there is a caller to reject.)
- **An automation validates the outcome of its effect through `onFailure`.** The external call it
  makes can fail in ways only the automation can classify, and `AutomationFailureAction` is that
  classification: retry in place, let independent work overtake, or stop for a human.
  `ExecutePaymentAutomation` in the payments example is the reference — each failure mapped to an
  action *and* an event, dead letters and retry-with-delay both being events the todo list projects.
- **Duplicate submission is an idempotency key, not a check.** "This request was already processed"
  cannot be validated by reading first — the duplicate can land between the read and the append. Give
  the raised event a key derived from the request (never from the attempt) and the storage silently
  ignores the repeat; an empty result means the work was already done, which for an at-least-once
  caller is success. Outbound events *must* carry one — the append is rejected otherwise — and
  `forbidIdempotencyKey()` is the greppable opt-out that costs exactly what it says.

## 6. Evolving a rule without breaking history

A new or tightened rule changes what may be *written from now on*; the events already stored were
valid under the rules of their day and stay readable under yours. Concretely:

- The rule lands in the factory (step 1) and the command (steps 2–4). Never in the canonical
  constructor of a persisted record, and never in an upcaster — `@Upcast` runs on the read path, and
  an upcaster that throws on legacy data manufactures the same poison event a throwing constructor
  does (`SerdeFailureTest` pins what that looks like).
- Where old data must be brought up to the new shape, an upcaster *converts* — defaults a missing
  field, maps a retired value — it does not *judge*.
- If existing events genuinely violate a new invariant, that is a domain fact, not a serde problem:
  record a correcting event, or have the decision model treat the legacy state explicitly. The store
  offers no way to reject history retroactively, on purpose.

---

## Shapes to avoid

**A throwing compact constructor on a record an event carries.** Nothing fails at the time; the rule
waits in ambush for the first read of an event written before it. See step 1 for where the same rule
goes safely.

**Deciding on a read model.** `context.read(...)` is explicitly outside the consistency boundary — a
"check first, then execute" against a read model is a race, and the append succeeds against history
the check never saw. [CHOOSING-A-READ-MODEL.md](CHOOSING-A-READ-MODEL.md) opens with this rule (its
step 0); anything a raised event depends on belongs in a `DecisionModel`.

**Validating in a projection's `when()` — a read model's or a translator's.** The read path again,
with the same economics as the constructor case plus a worse blast radius: a projector's first failure
is its last, so one unexpected event parks the whole read model or translator until restart. A
projection that meets an event it considers impossible should record or skip it, not throw at it.

**A uniqueness check without the boundary.** `claim.taken()` alone is the read-model race in decision
model clothing — correct in every test, wrong under concurrency. The empty boundary (step 4) is what
holds; the sequential check on top of it is just the better error message.

**`forbidIdempotencyKey()` on an at-least-once path.** An automation retries whatever did not
complete; a command it executes without a key publishes twice on exactly the retries the design
promises will happen.

**Rules in an upcaster.** Converting is its job; judging is a poison-event factory. See step 6.

---

## Quick reference

| | runs | guards against | surfaces as | retry? |
|---|---|---|---|---|
| Value object factory | at construction | data that cannot be said | exception at the edge | no — fix the input |
| Input check (command) | before any read | a malformed request | `IllegalArgumentException` | no |
| Decision model check | after projecting history | a rule history rejects | `BusinessException` + `CommandFailed` | no — the answer is no |
| DCB append check | inside the append | facts newer than the decision | `OptimisticLockingException` | yes — re-execute |
| Empty boundary (uniqueness) | inside the append | a concurrent duplicate claim | `OptimisticLockingException` | re-execute; then rejected by the check |
| Idempotency key | inside the append | the same request twice | empty result, silently | no — already done |
| Automation `onFailure` | after an effect failed | a failing external effect | `AutomationFailureAction` + an event | per the action |

**Testing the rules:** `CommandTest` speaks this page's language —
`given(events).when(command).then().error("Insufficient balance")` for steps 2–4 (it compares the root
cause's message, so the wrapped exception does not obscure the rule), `.event(expected, tags)` to pin
that the claim tag really is on the raised event, and `.noEvents()` to prove a rejection stored
nothing. The concurrent half of step 4 is the storage's promise, pinned per backend by the eventstore
TCK — your test asserts the sequential rejection and the tags, and leaves the race to
`ConcurrentOptimisticLockingTest`.
