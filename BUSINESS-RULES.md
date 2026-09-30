# Business rules and enforcement levels

Some of what a command checks is not a yes-or-no. The bank does not want accounts to go negative, but a
teller may make an exception. A large deposit is fine once somebody explains where the money comes
from. A withdrawal should have a description, and nothing stops one without. And before a user
submits anything, the screen should already show what will work, what will not, and what they are
allowed to override.

The framework models this on the **enforcement levels** of SBVR, the OMG's *Semantics of Business
Vocabulary and Business Rules*. This page is the guide: how a rule is declared, how a command checks
it, who may override it, what gets recorded, how a user sees it all before submitting, and how the
rules enforced *after the fact* are followed up. It is a sibling of
[WHERE-VALIDATIONS-GO.md](WHERE-VALIDATIONS-GO.md), which it extends at step 3; the banking example
(`BankingDomainWithClosingTheBooks`, `WithdrawCommand`, `DepositCommand`, and
`BankingBusinessRulesExample` to run) uses every level and is the code to copy.

---

## 1. Two kinds of "no"

SBVR separates two kinds of rule, and the framework has one tool for each:

| | SBVR calls it | example | breaking it | in a command |
|---|---|---|---|---|
| **The request makes no sense** | a *definitional* rule, a necessity | "this account does not exist", "the period is closed" | impossible: nothing to override | `BusinessException.when(...)`, thrown on the spot |
| **The business says you ought not** | a *behavioral* rule, an obligation | "a withdrawal must not make the balance negative" | possible: people can do otherwise | `context.check(rule, violated, message)`, judged by its enforcement level |

Keep the first kind as it is. No override can make a withdrawal from a non-existing account
meaningful, and reporting it as a "rule you may not override" would only put a rule in front of the
user where there is nothing to decide. Everything below is about the second kind.

## 2. Declaring a rule

```java
BusinessRule NO_OVERDRAFT = BusinessRule.of(
        "no-overdraft",                                              // id: wire format
        "A withdrawal must not make the balance of the account negative.")  // statement: wording
    .enforcedAt(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE);           // level: policy
```

- **The id is wire format.** It is the key a front end sends back to override the rule, it is stored
  in every recorded violation, and it is the value of the rule tags on the events. Treat it like an
  event type name: one token, no whitespace, and never renamed casually.
- **The statement is wording.** It is what the user is shown, in the language of the business. It is
  not stored, so it can be reworded freely.
- **The level is policy, deliberately kept out of the rule's meaning.** SBVR is explicit that an
  enforcement level can change without the rule changing — "from next month overdrafts are strictly
  enforced" is one word in the declaration and nothing else. Two rules with the same id are the same
  rule, whatever their levels. A rule declared without a level is strictly enforced: a rule nobody
  decided to relax is enforced.
- **Declare them on the context** with `builder.businessRules(...)`, typically from the slice that checks
  them (`builder.businessRules(NO_OVERDRAFT, ...)` in `configureCommand`). Checking a rule does not need
  it; being seen does: the declared rulebook is announced when the context starts, which is how a
  dashboard shows every rule with its statement and level — also the ones nobody has violated yet.
- **Where to put them:** with the rest of the domain's vocabulary, in the context definition. The
  rulebook is part of the ubiquitous language, and a rule that one slice checks is often followed up
  by another (section 9). `BankingDomainWithClosingTheBooks` keeps its rules, and the thresholds they
  name, in a `Business rules` section next to the entities.

## 3. The enforcement levels

| Level | A violation… | A front end shows | Recorded on the events as |
|---|---|---|---|
| `STRICTLY_ENFORCED` | blocks, always | an error | — |
| `DEFERRED_ENFORCEMENT` | never blocks; enforced later | a notice | `ENFORCEMENT_DEFERRED` |
| `PRE_AUTHORIZED_OVERRIDE` | blocks, unless overridden by an actor **the command authorizes** | a checkbox — or an error saying why not | `OVERRIDDEN` |
| `POST_JUSTIFIED_OVERRIDE` | blocks, unless overridden; the override must be justified afterwards | a checkbox | `JUSTIFICATION_PENDING` |
| `OVERRIDE_WITH_EXPLANATION` | blocks, unless overridden **with an explanation** | a checkbox and a text field | `OVERRIDDEN`, explanation included |
| `GUIDELINE` | never blocks | a hint | `GUIDELINE_NOT_FOLLOWED` |

- **Strictly enforced** differs from a `BusinessException` in what it is, not in its outcome: it is a
  named behavioral rule, reported next to every other violation when a command is evaluated, and
  relaxing it one day is a change of level rather than of code. *Banking: `maximum-withdrawal`.*
- **Pre-authorized override** is granted to actors who hold the authorization before the fact, and
  which actors those are is the command's decision (section 5). A command that takes no decision
  authorizes nobody. *Banking: `no-overdraft`, three exceptions per teller per day.*
- **Post-justified override** goes through on request, and leaves an obligation behind: an override
  never justified is a violation after all. *Banking: `large-withdrawal-justified`, followed up by
  `JustifyWithdrawalCommand`.*
- **Override with explanation** goes through when the actor explains why; the explanation is recorded.
  *Banking: `origin-of-funds-explained` on large deposits.*
- **Deferred enforcement** is strictly enforced, but not now: the command goes ahead and something
  enforces the rule later. *Banking: `balance-within-guarantee`, followed up by
  `ReportExcessBalanceAutomation`.*
- **Guideline** is advice. *Banking: `withdrawal-described`.*

## 4. Checking rules in a command

The shape is always the same: select decision models, reject what makes no sense, check the rules,
raise.

```java
@Override
public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
    var period = new ActivePeriodDecisionModel(accountId);
    var exceptionsToday = new OverdraftExceptionsTodayDecisionModel(context.actor(), today);
    var result = context.decisionModels(period, exceptionsToday);

    // the request makes no sense: rejected, there is nothing to override
    BusinessException.when(!period.accountExists(), "Account does not exist");

    // the bank's rules, each at its own enforcement level
    context.check(MAXIMUM_WITHDRAWAL, amount.compareTo(MAXIMUM_WITHDRAWAL_AMOUNT) > 0,
        "A withdrawal of " + amount + " exceeds the maximum of " + MAXIMUM_WITHDRAWAL_AMOUNT);
    context.check(NO_OVERDRAFT, balanceAfter.signum() < 0, "The balance would become " + balanceAfter)
        .overridableWhen(exceptionsToday.mayGrantAnother(), exceptionsToday.whyNot());
    context.check(WITHDRAWAL_DESCRIBED, description == null || description.isBlank(), "No description given");

    // raise as if everything were allowed: the kernel stops what may not go ahead
    result.raiseEvent(new MoneyWithdrawn(accountId, month, amount, description, context.ruleViolations()), tags);
}
```

- **`check` never throws.** It records whether the rule is violated and what to tell the actor — a
  message specific to this violation, where the statement is general. The kernel judges every check
  once the command has returned. That is what lets a command report **every** violated rule at once,
  instead of the first one, and what lets the same command be evaluated without being executed.
- **Raise as if everything were allowed.** The command does not branch on the outcome of its checks.
  If a violation stops the execution, the kernel rejects it with a `RuleViolationException` before
  anything is appended, so the event is never stored. If the execution goes ahead, the event carries
  exactly the violations it went ahead with.
- **Check on decision models.** A rule judged on facts outside the consistency boundary is judged on
  facts that can change underneath it. The rules come after `decisionModels(...)`, on what the models
  say, exactly as the `BusinessException`s do.
- **Check everything before reading `ruleViolations()`.** The events must record the judgement the
  execution is made on, so a `check` or an `overridableWhen` after that read is refused with an
  `IllegalStateException` — a bug, reported as `CommandFailed`.
- **Only commands check rules.** `check` is on `CommandContext`: a rule is judged on decision models, and
  a command is where they are read. A publisher, an automation or a translator decides nothing of its own
  — whatever it needs decided, it has a command decide.

## 5. Who may override: the command decides

```java
context.check(NO_OVERDRAFT, balanceAfter.signum() < 0, "The balance would become " + balanceAfter)
       .overridableWhen(exceptionsToday.mayGrantAnother(), exceptionsToday.whyNot());
```

Whether *this* actor may override *this* violation is, more often than not, a business rule of its
own, and a subtle one: a role granted through events, a maximum number of exceptions per actor per
day, a deviation small enough for a junior and too large for anyone but a manager. All of that needs
history, and history is what a command has in its decision models. So the decision is taken in the
command, with `overridableWhen(authorized, whyNot)`:

- **It sits inside the consistency boundary.** `OverdraftExceptionsTodayDecisionModel` is one of the
  command's decision models, so its query is part of the lock filter. Two overdrafts by the same teller
  racing past the quota cannot both succeed: the second append conflicts, and `executeWithRetry`
  re-decides on a count that includes the first.
- **`whyNot` is required when the answer is no**, because it is what the actor is shown instead of a
  checkbox: "You already granted 3 overdraft exceptions today, the maximum is 3".
- **The actor is `context.actor()`**, read from the tracing the command was executed with — the same
  value the kernel stores in the `x-actor` tag of every event, so the actor the decision is taken for
  and the actor history records are one. It is empty for an execution without an actor, and a
  decision that needs one should then say no. The framework's own actors (`"automation"`, `"system"`)
  are returned as they are.
- **Defaults when the command takes no decision:** nobody may make a pre-authorized override; anybody
  who asks may make a post-justified override or an override with explanation, and `overridableWhen`
  narrows that. On the other levels there is nothing to decide.

The history a quota counts is written by the kernel, not by the domain: every event raised with an
override carries `x-rule-overridden:<rule>`, and every event carries `x-actor`. So a per-actor count
across all accounts is one tag query:

```java
EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), RuleTags.overriddenBy(teller, NO_OVERDRAFT))
```

## 6. Overrides are an input of the command

```java
public record WithdrawCommand ( AccountId accountId, BigDecimal amount, String description,
                                LocalDate today, Overrides overrides )
        implements Command<BankingEvent>, Overriding { ... }

new WithdrawCommand(account, amount, "Kitchen", today, Overrides.of("no-overdraft"));
new DepositCommand(account, amount, "Car sold",
        Overrides.none().with(ORIGIN_OF_FUNDS_EXPLAINED, "Proceeds of selling a car, invoice on file"));
```

The overrides a user ticked are part of the request, exactly like the amount, so they are a component
of the command. A command accepts them by implementing `Overriding` (for a record, a component
`Overrides overrides` is all it takes). The kernel judges each override request:

- **A request is not a grant.** It is weighed against the rule's level and, for a pre-authorized
  override, against the command's own decision.
- **A request for a rule that is not violated is ignored and records nothing**, so a front end may
  simply send back every box that is ticked.
- **A command that does not implement `Overriding` overrides nothing.** That is the safe default for
  everything that builds commands without a user in front of it: automations, translators, retries,
  tests. A retry re-executes the same command, so it carries the same overrides and re-judges them on
  the facts as they are then.

## 7. What gets recorded

An execution that goes ahead with violations records them twice, and each record has its own job:

- **In the payload**, as `List<RuleViolation>` from `context.ruleViolations()`: the rule id, the level
  in force, the disposition, the command's message and the actor's explanation. This is the business
  fact itself ("this withdrawal was an exception to the overdraft rule"), and the only place free text
  like an explanation belongs. Only violations that did not block are in it.
- **As tags**, added by the kernel to every event of the append, whether or not the command also
  recorded the violations in its payload:

  | Disposition | Tags |
  |---|---|
  | `OVERRIDDEN` | `x-rule-overridden:<rule>` |
  | `JUSTIFICATION_PENDING` | `x-rule-overridden:<rule>`, `x-rule-justification-pending:<rule>` |
  | `ENFORCEMENT_DEFERRED` | `x-rule-deferred:<rule>` |
  | `GUIDELINE_NOT_FOLLOWED` | `x-rule-not-followed:<rule>` |

  They make the violations queryable without knowing any payload: by a decision model counting an
  actor's overrides, by a todo list collecting what is still to be justified or enforced, by an
  auditor. `RuleTags` has the constants and the helpers.

**Who made the exception** is the `x-actor` tag the kernel writes on every event anyway; read it back
with `Tracing.readFrom(event).actor()`. It is deliberately not repeated in the payload.

A domain that wants an explicit event for an exception (`OverdraftApproved`) raises one, in the same
append. `RuleViolation` is the generic shape for everything else. Like every record carried inside an
event payload, its constructor is lenient and never throws.

## 8. Evaluating before executing

```java
Evaluation evaluation = bank.evaluate(command, tracing);    // nothing is appended
```

`evaluate` runs the command exactly as `execute` would — decision models projected, its
`BusinessException`s thrown, its rules checked and judged against the overrides it carries — and
stops short of the append. It answers an `Evaluation`:

| Outcome | means |
|---|---|
| `REJECTED` | the command threw a `BusinessException`: the request makes no sense (`rejection()` says why) |
| `BLOCKED` | at least one violation blocks for this actor |
| `NEEDS_OVERRIDE` | nothing blocks, but at least one violation needs an override this actor may give |
| `WOULD_SUCCEED` | nothing stops it; there may still be advice, deferred enforcements and accepted overrides |

and a `RuleJudgement` per violated rule, in the order the command checked them. A judgement's
`verdict` is what a front end lays out:

| Verdict | render |
|---|---|
| `BLOCKS` | an error, with `reason()` when the level allows overrides but the command said no for this actor |
| `OVERRIDE_REQUIRED` | a checkbox; with a text field when `explanationRequired()` |
| `OVERRIDDEN` | a ticked checkbox |
| `DEFERRED` | a notice |
| `ADVISED` | a hint |

What evaluating deliberately does **not** do:

- **It appends nothing, spends no idempotency key and emits no `CommandExecuted`.** A front end may
  evaluate on every change of a form, and the monitoring record is a record of what happened, not of
  what was previewed. It is observed as its own `Observation.CommandEvaluation`.
- **It promises nothing about the execution that follows.** The facts may change in between, and the
  execution judges every rule again. That is also why an override is acknowledged per rule and
  re-judged, rather than handed out as a token.
- **It is on `ApplicationCapabilities`, beside `execute`**: the code that submits a command is the code
  that previews it. Automations and translators have no user to show a preview to.

When an execution is rejected on its rules, the `RuleViolationException` carries the same
`Evaluation`. So a front end has **one shape to render**, whether it checked beforehand or just
submitted. The cheapest front end skips the preview altogether: it submits, gets the violations back,
shows the checkboxes, and submits again.

## 9. Binding to HTTP

Previewing and executing are two methods on one resource, carrying the same body:

```java
routes.query("/api/withdrawals", ctx -> ctx.header("Cache-Control", "no-store")
                                          .json(banking.evaluate(toCommand(ctx), tracingOf(ctx))));
routes.post ("/api/withdrawals", ctx -> { banking.execute(toCommand(ctx), tracingOf(ctx)); ctx.status(201); });
app.exception(RuleViolationException.class, (e, ctx) -> ctx.status(422).json(e.evaluation()));
```

| | request | response |
|---|---|---|
| preview | `QUERY /api/withdrawals` with the command's body | `200` with the evaluation, **also when it is blocked**: the preview itself succeeded |
| execute | `POST /api/withdrawals` with the same body | `201`; `422` with the evaluation when rejected on its rules; `409` on an `OptimisticLockingException` that survived the retries |

```json
{ "accountId": "acc-1", "amount": 250, "description": "Kitchen",
  "overrides": [ { "rule": "no-overdraft" },
                 { "rule": "large-withdrawal-justified", "explanation": "Customer is waiting" } ] }
```

The evaluation renders as its components, and each judgement also carries `overridable` and
`explanationRequired`, the two facts a front end lays its widgets out by:

```json
{ "outcome": "NEEDS_OVERRIDE", "rejection": null,
  "judgements": [ { "rule": "no-overdraft",
                    "statement": "A withdrawal must not make the balance of the account negative.",
                    "enforcementLevel": "PRE_AUTHORIZED_OVERRIDE", "message": "The balance would become -150",
                    "verdict": "OVERRIDE_REQUIRED", "reason": null, "explanation": null,
                    "overridable": true, "explanationRequired": false } ] }
```

- **`QUERY`** is the HTTP method for a safe, idempotent request that carries a body, which is exactly
  what an evaluation is. Keeping it on the same URI as the `POST` means one resource and two methods,
  rather than a second "check" resource to keep in step with the first.
- **`Cache-Control: no-store`.** `QUERY` responses may be cached, keyed on the body, but an evaluation
  depends on the current state and on who is asking.
- **Cross-origin callers** need `QUERY` in the CORS configuration: it is not a CORS-safelisted method,
  so it triggers a preflight. Check that proxies and gateways in between let it through, and note that
  OpenAPI describes a `query` operation only from 3.2.
- **Overrides go in the body**, because they are the user's decision, like every other field of the
  form.

## 10. Following up after the fact

Two levels are enforced after the command: post-justified override, and deferred enforcement. Neither
needs anything from the framework beyond the recorded violation. The follow-up is an ordinary slice,
fed by the tag the kernel wrote.

The command that finishes a follow-up says which violation it settles, and the kernel links the two:

```java
result.raiseEvent(new WithdrawalJustified(accountId, withdrawal,
        context.justifies(LARGE_WITHDRAWAL_JUSTIFIED, EventId.of(withdrawal), justification)), tags);
```

| call | tag on every event of the append | returns |
|---|---|---|
| `context.justifies(rule, eventId, text)` | `x-rule-justified:<rule>@<event id>` | a `RuleFollowUp` of kind `JUSTIFIED` |
| `context.enforces(rule, eventId, note)` | `x-rule-enforced:<rule>@<event id>` | a `RuleFollowUp` of kind `ENFORCED` |

The pending tag stays on the original event for good, because events are never rewritten; the link tag
is what says it was settled, and by whom (`x-actor`), without anyone knowing the domain event that did
it. Record the `RuleFollowUp` in the payload, as the violations are: the text is free text and belongs
there, not in a tag. Whether the event needs the follow-up is the command's decision on its decision
models; a follow-up in an execution that raises nothing is refused with an `IllegalStateException`.

**Post-justified override** (`features/justifywithdrawal`):

- `OverridesAwaitingJustificationReadModel` selects `MoneyWithdrawn` events by
  `x-rule-justification-pending:large-withdrawal-justified`, and subtracts the `WithdrawalJustified`
  events.
- `JustifyWithdrawalCommand` records the justification, through `context.justifies(...)`. Justifying a
  withdrawal that needs none, or that is already justified, makes no sense and is a `BusinessException`.
- Escalating an override that is never justified is an automation over a todo list of the same shape,
  offering an item once its withdrawal is older than the deadline. `PaymentsToExecuteTodoList` shows a
  todo list that withholds what is not yet due.

**Deferred enforcement** (`features/excessbalance`):

- `DepositsAboveGuaranteeTodoList` selects `MoneyDeposited` events by
  `x-rule-deferred:balance-within-guarantee`, and drops each item once `ExcessBalanceReported` names
  its deposit.
- `ReportExcessBalanceAutomation` executes `ReportExcessBalanceCommand` for each item, with an
  idempotency key derived from the deposit, so an item handed over twice is reported once. The command
  links the report to the deposit with `context.enforces(...)`.

**Auditing.** Everything above is visible to an observer without any domain class: the rule tags and the
link tags on the events, the actor on every event, the rulebook on `BoundedContextStarting`, and — for an
execution the rules stopped — the judgements on `CommandRejected.ruleJudgements`. The dashboard's Business
Rules screen is built on exactly these.

```
DepositCommand → MoneyDeposited [x-rule-deferred] → DepositsAboveGuaranteeTodoList
               → ReportExcessBalanceAutomation → ReportExcessBalanceCommand → ExcessBalanceReported
```

## 11. Testing

`CommandTest` speaks this page's language:

```java
given().event(accountOpened(), tags)
    .as("alice")                                                    // the actor: context.actor()
    .whenEvaluated(new WithdrawCommand(account, amount, "tv", today, Overrides.none()))
    .thenEvaluation()                                               // what a teller would be shown
    .needsOverrideOf(NO_OVERDRAFT)
    .judged(WITHDRAWAL_DESCRIBED, Verdict.ADVISED);

given()...
    .when(new WithdrawCommand(account, amount, "tv", today, Overrides.of("no-overdraft")))
    .then()
    .rulesViolated()                                                // rejected by the kernel
    .notOverridable(NO_OVERDRAFT, "Only an identified teller can authorize an overdraft");

given()...
    .when(...)
    .then()
    .event(new MoneyWithdrawn(..., List.of(new RuleViolation(...))), Tags.of(RuleTags.overridden(NO_OVERDRAFT)));
```

- **`as(actor)`** acts as that actor from that point on: events seeded after it carry the actor's
  `x-actor` tag, and the command is executed or evaluated for that actor.
- **`whenEvaluated`** fails if anything was appended; **`thenEvaluation()`** and
  **`then().rulesViolated()`** both hand out `EvaluationAssertions`, since a rejection carries an
  evaluation too.
- **A quota is tested by seeding the history it counts.** Earlier overrides are appended as the actor,
  tagged `RuleTags.overridden(rule)`, which is exactly how the kernel stores them.
  `WithdrawCommandTest` is the worked example.

---

## Shapes to avoid

**Throwing a `BusinessException` for a behavioral rule.** It stops at the first violation, cannot be
overridden, and cannot be relaxed without changing code. If people can do otherwise, it is a rule with
an enforcement level.

**Checking a rule for a request that makes no sense.** "Account does not exist" as a strictly enforced
rule puts a decision in front of the user where there is none. It is a `BusinessException`.

**Deciding who may override from outside the command.** A role check in the HTTP layer cannot count
anything, and it sits outside the consistency boundary. Pass what the decision needs to the command,
and let `overridableWhen` decide.

**Branching on a check.** `if ( violated ) return;` skips the kernel's judgement: a violation that
should have been reported is silently swallowed, and an override that should have been recorded is not.
Check and raise; the kernel stops what may not go ahead.

**Recording violations yourself instead of `ruleViolations()`.** A hand-built list can disagree with the
kernel's judgement, and the recorded audit then says something that did not happen.

**Treating an evaluation as a reservation.** It is a preview. The execution judges again, on the facts
as they are then.

---

## Rejected alternatives

- **Splitting a command into a validator and an executor.** The check only holds inside the consistency
  boundary that the execution's decision models pin, so the executor would have to check everything
  again. The result is two copies of every rule, drifting apart. One `execute` and a kernel that can
  stop short of the append gives the preview for free.
- **An override policy configured on the builder.** It cannot read history, so it cannot express a
  quota or a role granted through events. It sits outside the boundary, so two overrides can race past
  it. And it cannot be tested with the command's own given/when/then. `overridableWhen` in the command
  has none of these problems.
- **`OPTIONS`, or a dry-run flag on the `POST`, for the preview.** `OPTIONS` describes what a resource
  supports, has no agreed body semantics, and is what browsers use for a CORS preflight. A flag makes
  one method on one URI mean two different things. `QUERY` is the method made for this.
- **Overrides carried on `Tracing`.** `Tracing` continues down a flow — into translators and correlated
  automations — and an override must never reach a later step the user did not see. An override is part
  of the request, so it is part of the command.
- **A `check` that throws at the first violation.** It could report only one rule at a time, and it
  would make evaluating a command impossible without executing it.

---

## Quick reference

| | where | outcome |
|---|---|---|
| Request that makes no sense | `BusinessException.when(...)` after `decisionModels(...)` | rejected: `BusinessException`, `CommandRejected`; evaluation `REJECTED` |
| Behavioral rule | `context.check(rule, violated, message)` | judged after `execute` by its enforcement level |
| Who may override | `.overridableWhen(authorized, whyNot)` on the check, on decision models | `BLOCKS` with `reason`, or `OVERRIDE_REQUIRED` / `OVERRIDDEN` |
| What the user asked for | `Overrides` component, `implements Overriding` | override requests the kernel judges |
| What to record | `context.ruleViolations()` in the payload; rule tags added by the kernel | audit, quota counts, follow-ups |
| Preview | `evaluate(command, tracing)`; HTTP `QUERY` | `Evaluation`, nothing appended |
| Rejected execution | `RuleViolationException` (a `BusinessException`) | carries the same `Evaluation`; HTTP `422` |
| Follow-up | todo list or read model on `RuleTags` | justification, deferred enforcement |
| Settling a follow-up | `context.justifies(...)` / `context.enforces(...)` | `x-rule-justified` / `x-rule-enforced` link tag, `RuleFollowUp` for the payload |
| Rulebook | `builder.businessRules(...)` | announced on `BoundedContextStarting` |
