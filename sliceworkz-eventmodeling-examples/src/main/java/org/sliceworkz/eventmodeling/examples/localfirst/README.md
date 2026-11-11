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
| conflict resolution | a `MultiValueRegister` per to-do status; concurrent writes resolved by `TodoStatus.CONCURRENT_PRIORITY` |

**Operations are replicated, not commands.** The command runs once, where the user is, against what that
replica knows. The event it raises is a decided fact, and the other replicas take it in as it is through
the framework's `event(...)` capability for settled facts. Re-running the command elsewhere could decide
differently (the to-do might be dropped there already), and the replicas would diverge.

**Statuses.** Adding a to-do makes it `PLANNED`; `MarkTodoOngoing` needs `PLANNED`, `MarkTodoDone` needs
`ONGOING`, `MarkTodoPlanned` needs `ONGOING` or `DONE`, `DropTodo` anything but `DROPPED`. These rules are
checked locally. Concurrent changes are all kept (`concurrentStatuses`) and the one that counts is picked
by one rule every replica applies alike: a drop wins, otherwise the status that got furthest. A change made
after seeing the conflict replaces all the concurrent ones.

**What local first cannot give you.** A rule over one replica's own operations holds. A rule over several
(no two open to-dos with the same title) holds per replica, but two offices adding the same title while
apart both succeed. `PeerToPeerTodoListTest.aRuleOverSeveralOperationsHoldsLocallyButNotAcrossAPartition`
shows it: such a rule needs coordination, or a compensating step once the conflict is seen.

## Tests

- `PeerToPeerTodoListTest`: three offices linked directly; partitions, concurrent changes, drop versus
  change, an operation arriving before its cause, duplicate delivery
- `CentralServerTodoListTest`: offices linked to a central replica only, which relays and decides nothing
- `ConvergenceTest`: random work under random partitions in both topologies, and the operations of a run
  delivered to fresh replicas in shuffled, duplicated batches; every replica ends in the same state
- `crdt/VectorClockTest`, `crdt/MultiValueRegisterTest`: the building blocks

## Where this would go next

- an operation of another replica arriving as an inbound event and a translator taking it in, and a
  publisher/dispatcher sending local operations out, instead of `Network` calling the servers
- the buffer of operations waiting for their cause is in memory; anti-entropy resends them after a restart
- the decision model reads the replica's whole stream, to compute the next stamp; a real one would keep the
  clock apart, and narrow the boundary per to-do
