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
| membership | a sparse vector clock; joining, retiring and pruning as replicated operations, compared on effective clocks |
| a compromised replica | `ReplicaDistrusted` from the security office; its later operations set aside, dependent honest changes reviewed |
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

**A replica taken over.** The security office (`TodoListDomain.SECURITY_OFFICE`) can declare an office
distrusted from one of its operations on (`DistrustReplica`, raising `ReplicaDistrusted`). The cut is in
CRDT coordinates, `(replica, operation number)`: event ids differ per replica, and the compromised office's
clock cannot be believed. A declaration from any other replica counts for nothing, and a cut only ever
moves earlier, so declarations converge like everything else. What follows, all computed by
`TodoListFold` from the set of operations held, so every replica agrees:

- every later operation of the distrusted office is **set aside**: kept in every store as forensic record,
  left out of the to-do list. A write it had overwritten counts again
- an honest change whose causal past holds a set-aside operation on the same to-do is **suspect**, and so is
  one built on a suspect change. The vector clock says what was *seen*; narrowing it to the change's own
  to-do is what keeps the list short (a to-do is these commands' consistency boundary, apart from the title
  uniqueness check)
- a suspect change whose rule still holds on its to-do as it stood in its causal past, without the set-aside
  operations, **stands** unreviewed: starting a to-do the attacker only renamed
- one whose rule no longer holds **waits for a reviewer** (`CompromiseReviewReadModel`) and blocks its to-do,
  like a title conflict: finishing a to-do only the attacker had started, settling a title on the attacker's
  rename. `ReviewTodoChange` confirms it or reverts it; a reverted change is set aside too, and what was built
  on it is judged again. Two reviewers disagreeing while apart: revert wins
- honest work on a to-do only the attacker created is set aside with it

What this proof of concept trusts, and a real one cannot: the origin an operation claims. Without every
operation signed by its office, an attacker can stamp operations as another office, or as the security
office; without each office's operations chained by hash, it can send different operations under one number
to different offices, which never converge. Effects outside the system (an e-mail, a payment) are not undone
by setting an operation aside, and need compensating actions.

**Offices joining and leaving.** Correctness needs no membership: a vector clock is a sparse map, and a
replica nobody has heard of counts as 0. So:

- **joining** (`TodoListServer.joining`, `JoinNetwork`): a newcomer catches up from a sponsor with everything
  the sponsor holds, in its causal order, then says it joined (`OfficeJoined`). Nobody has to agree or even be
  reachable
- **an office away for a long time** is not gone: nothing ended it, and when it comes back it merges
- **retiring** (`RetireOffice`): the office's last operation; every command on it is refused afterwards, and
  anything stamped later by it is set aside
- **removing a lost office** (`removeOffice`): the security office's distrust declaration, without the suspicion
  - what it did up to the cut stands, anything later is set aside should it ever turn up
- **pruning** (`PruneRetiredOffice`, `RetiredOfficePruned`): new stamps leave a retired office's entry out, so
  clocks stop growing with every office that ever existed. A stamp without the entry would look *concurrent*
  with the retired office's operations, so every operation is compared on its **effective** clock: its own,
  with the retired office's last operation filled back in for every pruning in its causal past. Because the
  pruning is itself an operation, no office has to wait for the others: its stamp still carries the entry, and
  causal delivery hands every replica the retired office's operations, then the pruning, then any stamp
  relying on it. A removed (distrusted) office is not pruned, as honest operations may have seen it beyond the
  cut; stamps already stored keep their full clocks, since history is never rewritten

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
- `CompromisedReplicaTest`: an office taken over, its operations set aside, honest work standing or waiting
  for review, confirmed, reverted, judged again, and declarations from anyone but the security office ignored
- `MembershipTest`: an office joining through a sponsor while another is unreachable, retiring, pruned from
  new stamps with later changes still read as later, a newcomer after a pruning, a pruning arriving only after
  what it relies on, a lost office removed, and one that was away a long time merging
- `ConvergenceTest`: random work under random partitions in both topologies, and the operations of a run
  delivered to fresh replicas in shuffled, duplicated batches, and random work with an office taken over
  partway and reviews along the way, and with an office retiring, a newcomer joining and prunings along the
  way; every replica ends in the same state
- `crdt/VectorClockTest`, `crdt/MultiValueRegisterTest`: the building blocks

## Where this would go next

- an operation of another replica arriving as an inbound event and a translator taking it in, and a
  publisher/dispatcher sending local operations out, instead of `Network` calling the servers
- the buffer of operations waiting for their cause is in memory; anti-entropy resends them after a restart
- the decision model reads the replica's whole stream, to compute the next stamp; a real one would keep the
  clock apart, and narrow the boundary per to-do
