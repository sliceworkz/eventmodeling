# Local-first to-do list: an operation-based CRDT on the Sliceworkz libraries

A proof of concept. Several replicas of one to-do list (one per office, say) each decide on their own,
keep working while they cannot reach each other, and reach the same state once they exchange what they
did. No networking: `Network` simulates links, partitions and anti-entropy, and the tests decide when
replicas talk.

## How it maps onto Event Modeling and DCB

| CRDT concept | Here |
|---|---|
| replica | `TodoListServer`: a `TodoList` bounded context on an event store of its own |
| prepare (local, with rules) | a `Command` deciding on `ReplicaStateDecisionModel`, this replica's whole stream |
| effect operation | the domain event it raises (`TodoAdded`, `TodoMarkedOngoing`, …), carrying an `OperationStamp` |
| dot + causal context | `OperationStamp`: origin replica, its sequence number, and the `VectorClock` it was decided on |
| exactly-once effect | the operation id is the idempotency key the event is stored under, on every replica |
| causal delivery | `TodoListServer.receive` buffers an operation until what it was decided on is there |
| anti-entropy | `ReplicationLogReadModel.missingAt(peerClock)`, exchanged by `Network.sync` |
| state | `TodoListFold`, shared by the decision model and the `TodoListReadModel` |
| conflict resolution | a `MultiValueRegister` per to-do title and per status; concurrent statuses settled by a business rule, concurrent renames a conflict a person settles |

**Operations are replicated, not commands.** The command runs once, where the user is, against what that
replica knows. The event it raises is a decided fact, and the other replicas take it in as it is through
the framework's `event(...)` capability for settled facts. Re-running the command elsewhere could decide
differently (the to-do might be dropped there already), and the replicas would diverge.

**Statuses.** Adding a to-do makes it `PLANNED`; `MarkTodoOngoing` needs `PLANNED`, `MarkTodoDone` needs
`ONGOING`, `MarkTodoPlanned` needs `ONGOING` or `DONE`, `DropTodo` anything but `DROPPED`. These rules are
checked locally. Concurrent status changes are all kept (`concurrentStatuses`) and settle themselves by a
business rule every replica applies alike (`TodoStatus.CONCURRENT_PRIORITY`): a drop wins, otherwise the
**earliest state wins**. An office that puts a to-do back on the plan, or keeps it ongoing, has a reason the
office that finished it did not know about, so the step back counts for more than the step forward.

**Titles, and the conflict a person settles.** `RenameTodo` changes a to-do's title. Two offices renaming
the same to-do differently while apart is a conflict no rule can settle: which wording is right is for a
person to say. Until then the to-do has no agreed title (`TodoView.title` is `null`, `concurrentTitles`
holds both), `TodoConflictsReadModel` lists it with the rename each office made, and every command on it is
refused until someone runs `SettleTodoTitle`, with one of the titles or a new one. The settlement is an
operation like any other: its clock covers both renames, so it replaces them everywhere. Renaming the same
way is no conflict. Two things follow from deciding locally. A conflict holds back only the replicas that
know of it; an office that has not synced yet works on, and its status changes count. And two offices can
settle the same conflict while apart: on the same title that ends it, on different titles it is a new
conflict, to be settled again.

**What local first cannot give you.** A rule over one replica's own operations holds. A rule over several
(no two open to-dos with the same title) holds per replica, but two offices adding the same title while
apart both succeed. `PeerToPeerTodoListTest.aRuleOverSeveralOperationsHoldsLocallyButNotAcrossAPartition`
shows it: such a rule needs coordination, or a compensating step once the conflict is seen.

## Tests

- `PeerToPeerTodoListTest`: three offices linked directly; partitions, concurrent changes, drop versus
  change, an operation arriving before its cause, duplicate delivery
- `CentralServerTodoListTest`: offices linked to a central replica only, which relays and decides nothing
- `TodoConflictTest`: a title conflict shown everywhere, actions refused, settled on one of the titles or a
  new one, settled twice alike and differently, and the offices that had not heard of it
- `ConvergenceTest`: random work under random partitions in both topologies, and the operations of a run
  delivered to fresh replicas in shuffled, duplicated batches; every replica ends in the same state
- `crdt/VectorClockTest`, `crdt/MultiValueRegisterTest`: the building blocks

## Where this would go next

- an operation of another replica arriving as an inbound event and a translator taking it in, and a
  publisher/dispatcher sending local operations out, instead of `Network` calling the servers
- the buffer of operations waiting for their cause is in memory; anti-entropy resends them after a restart
- the decision model reads the replica's whole stream, to compute the next stamp; a real one would keep the
  clock apart, and narrow the boundary per to-do
