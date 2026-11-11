/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.examples.localfirst;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.OfficeJoined;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.OfficeRetired;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.ReplicaDistrusted;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.RetiredOfficePruned;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoAdded;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoChange;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoChangeReviewed;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoDropped;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedDone;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedOngoing;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoMarkedPlanned;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoRenamed;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.TodoTitleSettled;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.Verdict;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.MultiValueRegister;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.VectorClock;

/**
 * The one fold of the to-do list, used by the decision model a command decides on and by the read models a
 * user reads, so they cannot disagree on what the operations mean.
 *
 * <p>A to-do is two registers, each written by operations: its title and its status. Concurrent status
 * changes resolve by a business rule ({@link TodoStatus#CONCURRENT_PRIORITY}); concurrent renames to
 * different titles are a <b>conflict</b> a person settles.
 *
 * <p><b>Compromised replicas.</b> The security office can declare a replica distrusted from one of its
 * operations on ({@link ReplicaDistrusted}). Every later operation of that replica is <i>set aside</i>:
 * kept in the store, left out of the to-do list. An honest change whose causal past holds a set-aside
 * operation on the same to-do is <i>suspect</i>, and so is one built on a suspect change. A suspect change
 * whose rule still holds on its to-do as it was without the set-aside operations stands without anyone
 * looking at it; one whose rule no longer holds is <i>pending review</i>, and blocks its to-do until a
 * reviewer confirms or reverts it. A reverted change is set aside too.
 *
 * <p>Because a declaration can arrive after the operations it sets aside, the state is computed from the
 * whole set of operations held: declarations and reviews first, then the to-do changes in the order this
 * replica stored them, which is causally consistent. Everything decided about a change depends only on its
 * causal past and on the declarations and reviews held, so replicas holding the same operations agree.
 *
 * <p><b>Offices joining and leaving.</b> A newcomer needs nothing from anyone: it counts as 0 until it
 * operates. A retired office makes no more operations, and any office that knows of the retirement may
 * prune its entry from new stamps ({@link RetiredOfficePruned}). Since a stamp without the entry would
 * otherwise look concurrent with the retired office's operations, every operation is compared on its
 * <i>effective</i> clock: its own, with the retired office's last operation filled back in for every pruning
 * it has seen.
 *
 * <p>Pruning needs no wait for every office to have seen the retirement, because it is an operation: its own
 * stamp still carries the retired office's entry, and a stamp leaving the entry out has the pruning in its
 * causal past. Causal delivery therefore hands every replica the retired office's operations before the
 * pruning, and the pruning before any stamp that relies on it.
 */
public final class TodoListFold {

	/** One to-do as the fold holds it. */
	public record Todo ( TodoId todoId, MultiValueRegister<String> title, MultiValueRegister<TodoStatus> status, boolean underReview ) {

		/** Whether concurrent renames are waiting for someone to settle the title. */
		public boolean inConflict ( ) {
			return title.values().size() > 1;
		}

		/** The title every replica agrees on; {@code null} while renames are in conflict. */
		public String agreedTitle ( ) {
			return inConflict() ? null : title.values().iterator().next();
		}

		/** The title to show and sort by: the agreed one, or the first candidate while in conflict. */
		public String displayTitle ( ) {
			return title.values().stream().sorted().findFirst().orElseThrow();
		}

		public TodoStatus resolvedStatus ( ) {
			return status.resolved(TodoStatus.CONCURRENT_PRIORITY);
		}

		public boolean dropped ( ) {
			return resolvedStatus() == TodoStatus.DROPPED;
		}

	}

	/** A replica the security office distrusts, from the operation after {@code trustedUpTo} on. */
	public record Distrust ( ReplicaId replica, long trustedUpTo, String reason ) { }

	/** An operation held but left out of the to-do list, and why. */
	public record SetAside ( String operationId, ReplicaId decidedBy, TodoId todoId, String change, String why ) { }

	/** An honest change made on compromised input that no longer holds without it, waiting for a reviewer. */
	public record PendingReview ( TodoId todoId, String operationId, ReplicaId decidedBy, String change ) { }

	/** Where an office stands in the network. */
	public enum MemberStatus { ACTIVE, RETIRED, PRUNED, REMOVED }

	/**
	 * One office as this replica knows it: who sponsored it (null for a founder, or one that never said),
	 * and the last operation of a retired or removed one.
	 */
	public record Member ( ReplicaId replica, MemberStatus status, ReplicaId sponsoredBy, Long lastOperation ) { }

	private record State ( Map<TodoId, Todo> todos, List<Distrust> distrusted, List<SetAside> setAside, List<PendingReview> pending,
			List<Member> members, Map<ReplicaId, Long> retired, Set<ReplicaId> pruned ) { }

	private final List<TodoEvent> operations = new ArrayList<>();
	private VectorClock seen = VectorClock.empty();
	private State state;

	public void apply ( TodoEvent event ) {
		operations.add(event);
		seen = seen.mergedWith(event.stamp().clock());
		state = null;
	}

	/** Everything this replica has seen, compromised operations included: what its next operation is based on. */
	public VectorClock seen ( ) {
		return seen;
	}

	public Optional<Todo> todo ( TodoId todoId ) {
		return Optional.ofNullable(state().todos().get(todoId));
	}

	/** Every to-do, the dropped ones included, in an order every replica agrees on. */
	public List<Todo> todos ( ) {
		return state().todos().values().stream()
				.sorted(Comparator.comparing(Todo::displayTitle).thenComparing(t -> t.todoId().value()))
				.toList();
	}

	public List<Distrust> distrusted ( ) {
		return state().distrusted();
	}

	public Optional<Distrust> distrust ( ReplicaId replica ) {
		return state().distrusted().stream().filter(d -> d.replica().equals(replica)).findFirst();
	}

	public List<SetAside> setAside ( ) {
		return state().setAside();
	}

	public List<PendingReview> pendingReviews ( ) {
		return state().pending();
	}

	public List<Member> members ( ) {
		return state().members();
	}

	public boolean retired ( ReplicaId replica ) {
		return state().retired().containsKey(replica);
	}

	/** The retired offices whose entries new stamps leave out. */
	public Set<ReplicaId> pruned ( ) {
		return state().pruned();
	}

	/** The retired offices not pruned yet, with their last operation: any office knowing of a retirement may prune it. */
	public Map<ReplicaId, Long> prunable ( ) {
		State state = state();
		Map<ReplicaId, Long> prunable = new TreeMap<>(Comparator.comparing(ReplicaId::value));
		state.retired().forEach(( retired, last ) -> {
			if ( !state.pruned().contains(retired) ) {
				prunable.put(retired, last);
			}
		});
		return prunable;
	}

	// ----------------------------------------------------------------------------------------- computation

	private State state ( ) {
		if ( state == null ) {
			state = compute();
		}
		return state;
	}

	private State compute ( ) {
		Map<ReplicaId, Distrust> cuts = distrustDeclarations();
		Map<ReplicaId, Long> retirements = retirements(cuts);
		Map<String, OperationStamp> effective = effectiveStamps(cuts);
		Map<String, Verdict> verdicts = reviewVerdicts(cuts);

		Map<TodoId, Todo> todos = new LinkedHashMap<>();
		Map<TodoId, List<OperationStamp>> suspicious = new HashMap<>();
		Map<TodoId, List<TodoChange>> kept = new HashMap<>();
		Set<TodoId> blocked = new HashSet<>();
		List<SetAside> setAside = new ArrayList<>();
		List<PendingReview> pending = new ArrayList<>();

		for ( TodoEvent operation : operations ) {
			if ( !(operation instanceof TodoChange change) || change instanceof TodoChangeReviewed ) {
				continue;
			}
			TodoId todo = change.todoId();
			OperationStamp stamp = effective.get(change.stamp().operationId());
			if ( afterRetirement(stamp, retirements) ) {
				setAside.add(setAside(change, "made after its office retired"));
				continue;
			}
			if ( compromised(stamp, cuts) ) {
				setAside.add(setAside(change, "decided by a distrusted replica"));
				suspicious.computeIfAbsent(todo, t -> new ArrayList<>()).add(stamp);
				continue;
			}
			boolean suspect = suspicious.getOrDefault(todo, List.of()).stream().anyMatch(s -> s.happenedBefore(stamp));
			if ( suspect ) {
				suspicious.computeIfAbsent(todo, t -> new ArrayList<>()).add(stamp);
			}
			Verdict verdict = verdicts.get(stamp.operationId());
			if ( verdict == Verdict.REVERTED ) {
				setAside.add(setAside(change, "reverted after review"));
				continue;
			}
			if ( !(change instanceof TodoAdded) && !todos.containsKey(todo) ) {
				setAside.add(setAside(change, "about a to-do only distrusted operations created"));
				continue;
			}
			if ( suspect && verdict == null && !holdsWithoutWhatWasSetAside(change, stamp, kept.getOrDefault(todo, List.of()), effective) ) {
				pending.add(new PendingReview(todo, stamp.operationId(), stamp.origin(), describe(change)));
				blocked.add(todo);
			}
			kept.computeIfAbsent(todo, t -> new ArrayList<>()).add(change);
			write(todos, change, stamp);
		}

		todos.replaceAll((id, t) -> new Todo(id, t.title(), t.status(), blocked.contains(id)));
		Comparator<SetAside> bySetAside = Comparator.comparing((SetAside a) -> a.decidedBy().value()).thenComparing(SetAside::operationId);
		Comparator<PendingReview> byPending = Comparator.comparing((PendingReview p) -> p.decidedBy().value()).thenComparing(PendingReview::operationId);
		return new State(todos,
				cuts.values().stream().sorted(Comparator.comparing(d -> d.replica().value())).toList(),
				setAside.stream().sorted(bySetAside).toList(),
				pending.stream().sorted(byPending).toList(),
				members(cuts, retirements, effective), retirements, prunedOffices(cuts));
	}

	/** Retirements, from the retiring office itself; a compromised office's word does not count. */
	private Map<ReplicaId, Long> retirements ( Map<ReplicaId, Distrust> cuts ) {
		Map<ReplicaId, Long> retired = new HashMap<>();
		for ( TodoEvent operation : operations ) {
			if ( operation instanceof OfficeRetired r && r.stamp().origin().equals(r.replica()) && !compromised(r.stamp(), cuts) ) {
				retired.merge(r.replica(), r.stamp().counter(), Math::min);
			}
		}
		return retired;
	}

	private Set<ReplicaId> prunedOffices ( Map<ReplicaId, Distrust> cuts ) {
		Set<ReplicaId> pruned = new HashSet<>();
		for ( TodoEvent operation : operations ) {
			if ( operation instanceof RetiredOfficePruned p && !compromised(p.stamp(), cuts) ) {
				pruned.add(p.replica());
			}
		}
		return pruned;
	}

	/**
	 * Every operation's stamp with the entries it left out filled back in: for every pruning in its causal
	 * past, the pruned office up to its last operation. A pruning's own origin may be pruned later, so this
	 * repeats until nothing changes. It depends on the operation's causal past only, so every replica comes
	 * to the same effective clock for it.
	 */
	private Map<String, OperationStamp> effectiveStamps ( Map<ReplicaId, Distrust> cuts ) {
		List<RetiredOfficePruned> prunings = operations.stream()
				.filter(o -> o instanceof RetiredOfficePruned p && !compromised(p.stamp(), cuts))
				.map(o -> (RetiredOfficePruned) o)
				.toList();
		Map<String, OperationStamp> effective = new HashMap<>();
		for ( TodoEvent operation : operations ) {
			OperationStamp stamp = operation.stamp();
			VectorClock clock = stamp.clock();
			boolean changed = !prunings.isEmpty();
			while ( changed ) {
				changed = false;
				for ( RetiredOfficePruned p : prunings ) {
					boolean seenIt = clock.counterOf(p.stamp().origin()) >= p.stamp().counter();
					if ( seenIt && clock.counterOf(p.replica()) < p.lastOperation() ) {
						clock = clock.mergedWith(new VectorClock(Map.of(p.replica().value(), p.lastOperation())));
						changed = true;
					}
				}
			}
			effective.put(stamp.operationId(), stamp.withClock(clock));
		}
		return effective;
	}

	/** Every office this replica has heard of: in any clock, joined, or distrusted. */
	private List<Member> members ( Map<ReplicaId, Distrust> cuts, Map<ReplicaId, Long> retirements, Map<String, OperationStamp> effective ) {
		Map<ReplicaId, ReplicaId> sponsors = new HashMap<>();
		Set<ReplicaId> known = new HashSet<>(cuts.keySet());
		for ( TodoEvent operation : operations ) {
			effective.get(operation.stamp().operationId()).clock().counters().keySet().forEach(r -> known.add(ReplicaId.of(r)));
			if ( operation instanceof OfficeJoined j && j.stamp().origin().equals(j.replica()) ) {
				sponsors.putIfAbsent(j.replica(), j.sponsoredBy());
				known.add(j.replica());
			}
		}
		Set<ReplicaId> pruned = prunedOffices(cuts);
		return known.stream()
				.sorted(Comparator.comparing(ReplicaId::value))
				.map(r -> {
					if ( cuts.containsKey(r) ) {
						return new Member(r, MemberStatus.REMOVED, sponsors.get(r), cuts.get(r).trustedUpTo());
					}
					if ( retirements.containsKey(r) ) {
						return new Member(r, pruned.contains(r) ? MemberStatus.PRUNED : MemberStatus.RETIRED, sponsors.get(r), retirements.get(r));
					}
					return new Member(r, MemberStatus.ACTIVE, sponsors.get(r), null);
				})
				.toList();
	}

	private static boolean afterRetirement ( OperationStamp stamp, Map<ReplicaId, Long> retirements ) {
		Long last = retirements.get(stamp.origin());
		return last != null && stamp.counter() > last;
	}

	/** The declarations of the security office, the earliest cut per replica winning: they only ever move earlier. */
	private Map<ReplicaId, Distrust> distrustDeclarations ( ) {
		Map<ReplicaId, Distrust> cuts = new HashMap<>();
		for ( TodoEvent operation : operations ) {
			if ( operation instanceof ReplicaDistrusted d
					&& d.stamp().origin().equals(TodoListDomain.SECURITY_OFFICE)
					&& !d.replica().equals(TodoListDomain.SECURITY_OFFICE) ) {
				Distrust candidate = new Distrust(d.replica(), d.trustedUpTo(), d.reason());
				cuts.merge(d.replica(), candidate, (a, b) -> Comparator.comparingLong(Distrust::trustedUpTo)
						.thenComparing(Distrust::reason).compare(a, b) <= 0 ? a : b);
			}
		}
		return cuts;
	}

	/** The verdicts on suspect changes; two reviewers deciding differently without knowing it: revert wins, the cautious one. */
	private Map<String, Verdict> reviewVerdicts ( Map<ReplicaId, Distrust> cuts ) {
		Map<String, Verdict> verdicts = new HashMap<>();
		for ( TodoEvent operation : operations ) {
			if ( operation instanceof TodoChangeReviewed r && !compromised(r.stamp(), cuts) ) {
				verdicts.merge(r.operationId(), r.verdict(), (a, b) -> a == Verdict.REVERTED || b == Verdict.REVERTED ? Verdict.REVERTED : a);
			}
		}
		return verdicts;
	}

	private static boolean compromised ( OperationStamp stamp, Map<ReplicaId, Distrust> cuts ) {
		Distrust distrust = cuts.get(stamp.origin());
		return distrust != null && stamp.counter() > distrust.trustedUpTo();
	}

	/**
	 * Whether the change would still have been allowed on its to-do as it stood in its causal past, without
	 * the operations set aside: the same rules the commands check.
	 */
	private static boolean holdsWithoutWhatWasSetAside ( TodoChange change, OperationStamp stamp, List<TodoChange> keptOnTodo, Map<String, OperationStamp> effective ) {
		Map<TodoId, Todo> past = new LinkedHashMap<>();
		for ( TodoChange k : keptOnTodo ) {
			OperationStamp kStamp = effective.get(k.stamp().operationId());
			if ( kStamp.happenedBefore(stamp) ) {
				write(past, k, kStamp);
			}
		}
		Todo todo = past.get(change.todoId());
		if ( change instanceof TodoAdded ) {
			return true;
		}
		if ( todo == null ) {
			return false;
		}
		TodoStatus status = todo.resolvedStatus();
		return switch ( change ) {
			case TodoMarkedPlanned c -> !todo.inConflict() && (status == TodoStatus.ONGOING || status == TodoStatus.DONE);
			case TodoMarkedOngoing c -> !todo.inConflict() && status == TodoStatus.PLANNED;
			case TodoMarkedDone c -> !todo.inConflict() && status == TodoStatus.ONGOING;
			case TodoDropped c -> !todo.inConflict() && status != TodoStatus.DROPPED;
			case TodoRenamed c -> !todo.inConflict() && status != TodoStatus.DROPPED;
			case TodoTitleSettled c -> todo.inConflict();
			default -> true;
		};
	}

	private static void write ( Map<TodoId, Todo> todos, TodoChange change, OperationStamp stamp ) {
		switch ( change ) {
			case TodoAdded e -> todos.putIfAbsent(e.todoId(), new Todo(e.todoId(),
					MultiValueRegister.<String>empty().write(stamp, e.title()),
					MultiValueRegister.<TodoStatus>empty().write(stamp, TodoStatus.PLANNED), false));
			case TodoRenamed e -> writeTitle(todos, e, stamp, e.title());
			case TodoTitleSettled e -> writeTitle(todos, e, stamp, e.title());
			case TodoMarkedPlanned e -> writeStatus(todos, e, stamp, TodoStatus.PLANNED);
			case TodoMarkedOngoing e -> writeStatus(todos, e, stamp, TodoStatus.ONGOING);
			case TodoMarkedDone e -> writeStatus(todos, e, stamp, TodoStatus.DONE);
			case TodoDropped e -> writeStatus(todos, e, stamp, TodoStatus.DROPPED);
			case TodoChangeReviewed e -> { }
		}
	}

	private static void writeTitle ( Map<TodoId, Todo> todos, TodoChange change, OperationStamp stamp, String title ) {
		todos.computeIfPresent(change.todoId(), (id, t) -> new Todo(id, t.title().write(stamp, title), t.status(), false));
	}

	private static void writeStatus ( Map<TodoId, Todo> todos, TodoChange change, OperationStamp stamp, TodoStatus status ) {
		todos.computeIfPresent(change.todoId(), (id, t) -> new Todo(id, t.title(), t.status().write(stamp, status), false));
	}

	private static SetAside setAside ( TodoChange change, String why ) {
		return new SetAside(change.stamp().operationId(), change.stamp().origin(), change.todoId(), describe(change), why);
	}

	/** A change in words, for a reviewer. */
	public static String describe ( TodoChange change ) {
		return switch ( change ) {
			case TodoAdded e -> "added '" + e.title() + "'";
			case TodoRenamed e -> "renamed to '" + e.title() + "'";
			case TodoTitleSettled e -> "settled the title on '" + e.title() + "'";
			case TodoMarkedPlanned e -> "marked planned";
			case TodoMarkedOngoing e -> "marked ongoing";
			case TodoMarkedDone e -> "marked done";
			case TodoDropped e -> "dropped";
			case TodoChangeReviewed e -> e.verdict().name().toLowerCase() + " " + e.operationId();
		};
	}

}
