# Leader election: how leases spread over instances

Every leader-only processor (an automation, a SHARED read model's projector, a translator, a
publisher, a dispatcher, a policy) runs on exactly one instance of a deployment, the one holding
its lease in the event storage. `LeaderElector` holds one lease per processor and renews it every
heartbeat. This file covers one question: **which instance wins which lease**. The safety rules
(demote before takeover, bounded storage calls, fencing) are in the class javadoc of `LeaderElector`
and in the leader-election section of `CLAUDE.md`. They do not depend on anything here.

## What the storage offers

The eventstore's lease SPI is `requestLease(LeaseRequest(name, owner, priority, ttl))`, called by
every contender every heartbeat. It answers `LEADER`, `STANDBY`, or `LEADER_STEP_DOWN_REQUESTED`:
still leader, but a live contender with a **strictly higher** priority is waiting. The holder then
demotes its processor, gives it a heartbeat to finish its batch, and releases the lease. The storage
never revokes a live lease itself. The priority is sent **per request**, so per lease, and nothing
requires an instance to use the same priority for all of its leases. Everything below is built on
that one property, with no change to the storage.

## The two distributions

`BoundedContextBuilder.leadershipDistribution(...)`, default `BALANCED`.

### `FIRST_COME`

Every lease is requested with the configured priority (`leadershipPriority`, default 0). Equal
priorities never preempt, so a lease stays with whoever asked first. In practice:

- The first instance to start wins every lease in its synchronous start round, and the others
  stand by for everything.
- Splits such as 3/8 come only from moments when leases are up for grabs at once: two instances
  starting within moments of each other, a lease lapsing after a slow renewal, a yield, a context
  stopped and started. Whoever's request lands first takes each lease, and the split then stays.
- So the spread depends on what happened in the past and then sticks. Nothing ever rebalances it.

Its virtue is that it moves nothing, ever, unless an instance stops or fails.

### `BALANCED`: rendezvous hashing on the priority

Every lease is requested with

```
priority = (configured priority << 32) | weight(identity, lease name)
```

where `weight` is the first 32 bits of `SHA-256(identity + "\n" + lease name)`, unsigned
(`LeaderElector.weight`), and `identity` is the instance's logical and physical name
(`election/node-a`). This is rendezvous (highest-random-weight) hashing:

- **For every lease, the live contender that weighs most wins**, through the ordinary step-down
  protocol. Every instance computes the same assignment without knowing how many others there are
  or talking to them.
- **Each instance wins about 1/N of the leases.** `theWeightsSpreadLeasesEvenly` checks this over
  3000 leases and three identities. It balances the *number* of leases, not load: a hot automation
  and an idle projector count the same.
- **Nothing moves while the membership stays the same**, because the weights are fixed for the life
  of an elector. The holder keeps renewing and nobody outranks it.
- **A change of membership moves only the leases whose winner changed.** An instance that joins
  takes the leases it weighs most for, about 1/N. An instance that leaves has its leases spread
  over the survivors, each going to the next-heaviest one, rather than all landing on one instance.
- **A configured priority still decides first.** It sits in the high 32 bits, so a preferred
  instance wins every lease, and the weight only breaks ties among equal priorities. That is why
  `build()` refuses a configured priority outside the range of an `int` under `BALANCED`: it would
  overflow into a different order.

#### The identity, and why it is the physical name

The weight is computed from `logical/physical`, which an instance keeps across restarts. An instance
that restarts under the same name therefore wins back exactly the leases it had, and the instances
that stayed up keep their own leases throughout (`anInstanceRestartingUnderItsNameWinsBackExactlyWhatItHad`).

The alternative, hashing the lease owner (the process id plus a random token minted per elector),
spreads leases just as evenly but anew on every restart. The restarted instance comes back with
different weights and takes a different 1/N, so leases also move off survivors that had nothing to
do with the restart: roughly twice the moves per restart.

An instance without a physical name of its own (`InstanceFactory.determine(logical)`, physical
`default`) has nothing that tells it apart from its peers. Hashed on that, they would all weigh the
same for every lease, which is first-come again. Such an instance therefore uses its process id
(evenly spread, anew per restart) and logs that it does. Two instances misconfigured with the same
physical name weigh the same and fall back to first-come between them. That is safe, because the
lease owner stays unique per elector, but it does not balance.

The lease **owner** is unchanged: the process id plus a random token per elector. It identifies who
holds a lease, the weight decides who should. Keeping the two apart is what keeps two contexts in one
JVM, or two instances sharing a name by mistake, from mistaking each other's leases for their own.

#### The startup hold-back

`leadershipStartupHoldBack(Duration)`, `BALANCED` only, default zero (off).

Instances starting at about the same time (a rolling deploy, a cluster cold start) each find every
lease free and win whichever they ask for first, whatever their weight. Without a hold-back the
first one runs everything at once, and every lease the second weighs more for is then handed over:
a step-down, a heartbeat's pause, a re-seed on the new leader
(`withoutAHoldBackInstancesStartingTogetherMoveWork` counts them).

During the hold-back a lease won at start is **held but not acted on**. It is renewed, and the
processor is not promoted. A heavier contender appearing in that window gets a step-down answer for
it, and a lease whose processor was never told is released at once, before anything ran on it. When
the window has passed, the leases still held are promoted. Instances starting within a hold-back of
each other therefore settle who leads what without moving any work
(`instancesStartingTogetherSettleWithoutMovingWork`).

What it costs: every start runs its leader-only processors a hold-back later, a single instance's
included. What it does not help: an instance joining a deployment that is already running. There
the leases are held and running, and move through an ordinary hand-over.

How long to choose: a peer that started earlier than us registered as a contender in its own
synchronous start round, so by our first heartbeat round its requests are known to the storage. One
heartbeat covers instances starting within about a heartbeat of each other, which is the value to
set for a deployment that starts its replicas together. A longer hold-back covers more widely
staggered starts, at the price of a later start everywhere.

Why it is off by default: it delays every start, and most starts are a single instance (a
development run, an integration test) that has nobody to settle with. With a default of one
heartbeat, every test in this repository that awaits a SHARED read model or an automation within a
few seconds of starting a context failed, and an application's own tests would fail in the same
way. Moves at startup are also the cheapest moves there are: the processor has barely begun its
catch-up when it hands over. So the hold-back is opt-in, for the deployments where simultaneous
starts are routine.

#### No weight after a yield

A processor that keeps failing transiently on an instance yields its lease and stays out of that
election for one ttl, so another instance may take over (see "A projection failure is retried" in
`CLAUDE.md`). Under `BALANCED`, coming back with its full weight it would step the healthy instance
down again, fail again, and yield again: a hand-over per ttl for as long as its trouble lasts. So
after a yield the instance contends with its configured priority alone (weight 0) until it holds the
lease again, which it then only does when nobody else wants it. Once it holds the lease, the weight
is back (`aYieldedLeaseIsNotTakenBackFromTheHealthyInstance`).

## What each event costs

| | `FIRST_COME` | `BALANCED` |
|---|---|---|
| steady state | no moves | no moves |
| an instance dies | its leases go to whichever survivor asks first | they spread over the survivors |
| it comes back under its name | no moves, but it stays idle | it takes back exactly what it had |
| a transient lapse (a slow renewal costs a lease) | 1 move | 2 moves: out, and back once it is healthy |
| a yield on a failing instance | 1 move | 1 move |
| an operator stops a leader, and later restarts it | 1 move | 2 moves, when it weighs more: the restart puts it back in the lead |
| instances starting together | none, all on one instance | about 1/N per later instance, or none with a hold-back |
| a rolling start, staggered beyond the hold-back | none, all on the first instance | about 1/N of the leases per later instance |

The extra moves under `BALANCED` come from moving leases back to restored instances, and that is
exactly what produces the even spread. A deterministic assignment always restores itself, so any
disturbance costs a move out and later a move back.

## Alternatives, and optional extras for later

- **A fair-share count instead of an assignment.** Each instance would acquire free leases only up
  to its share (leases / live instances) and release its surplus when overloaded, preempting nothing.
  This moves fewer leases than rendezvous hashing: a lapse costs one move, not two. But it needs the
  number of live instances, which the lease SPI does not expose (Postgres has the contenders table,
  the SPI has no read of it). It also converges through instances that each decide alone what to
  give up, with nothing to keep two of them from releasing the same surplus at once. Rejected for
  now. It would be the shape to reach for if the SPI ever answered "how many live contenders".
- **Weighting by load rather than by count.** The lease is the unit of distribution, and a hot
  processor counts as one. A per-processor weight multiplier ("this automation counts as 5") could
  be layered on top by giving such a processor several virtual leases. That is not worth it until a
  deployment shows that a balanced count still leaves one instance overloaded.
- **A warm-up before preempting** (an optional extra). A newly started instance would contend
  without its weight for its first ttl or two. It would then never take a lease over during a crash
  loop, and leases would move to it only once it had stayed up. It adds a second delay setting for a
  case the hold-back does not cover (a joining instance, not a simultaneous start). Left out until
  restart loops show up in practice.
- **Stickiness for a recovering holder.** An instance that lost a lease to a transient lapse could
  contend without its weight for a while, turning the lapse's two moves into one. The cost is that
  the assignment no longer restores itself, so the spread drifts back toward first-come with every
  lapse. Rejected: lapses are rare, and the drift would come back as the original complaint.
- **A hold-back that waits for peers rather than for time.** The SPI has no "register as a
  contender without acquiring", so a starting instance cannot announce itself without winning free
  leases. The hold-back reaches the same result by winning them quietly and handing over what a
  heavier peer asks for before running anything.
- **`String.hashCode()` as the weight.** It is cheap, but names differing only in their last
  characters (`Automation1`, `Automation2`) hash to values close together, which hands runs of
  similarly named processors to one instance. SHA-256 is computed once per lease per elector, so its
  cost does not matter.
