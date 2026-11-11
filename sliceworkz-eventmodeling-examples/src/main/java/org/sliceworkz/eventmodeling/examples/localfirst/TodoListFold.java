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

import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.ReplicaDistrusted;
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

	private record State ( Map<TodoId, Todo> todos, List<Distrust> distrusted, List<SetAside> setAside, List<PendingReview> pending ) { }

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

	// ----------------------------------------------------------------------------------------- computation

	private State state ( ) {
		if ( state == null ) {
			state = compute();
		}
		return state;
	}

	private State compute ( ) {
		Map<ReplicaId, Distrust> cuts = distrustDeclarations();
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
			OperationStamp stamp = change.stamp();
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
			if ( suspect && verdict == null && !holdsWithoutWhatWasSetAside(change, kept.getOrDefault(todo, List.of())) ) {
				pending.add(new PendingReview(todo, stamp.operationId(), stamp.origin(), describe(change)));
				blocked.add(todo);
			}
			kept.computeIfAbsent(todo, t -> new ArrayList<>()).add(change);
			write(todos, change);
		}

		todos.replaceAll((id, t) -> new Todo(id, t.title(), t.status(), blocked.contains(id)));
		Comparator<SetAside> bySetAside = Comparator.comparing((SetAside a) -> a.decidedBy().value()).thenComparing(SetAside::operationId);
		Comparator<PendingReview> byPending = Comparator.comparing((PendingReview p) -> p.decidedBy().value()).thenComparing(PendingReview::operationId);
		return new State(todos,
				cuts.values().stream().sorted(Comparator.comparing(d -> d.replica().value())).toList(),
				setAside.stream().sorted(bySetAside).toList(),
				pending.stream().sorted(byPending).toList());
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
	private static boolean holdsWithoutWhatWasSetAside ( TodoChange change, List<TodoChange> keptOnTodo ) {
		Map<TodoId, Todo> past = new LinkedHashMap<>();
		keptOnTodo.stream().filter(k -> k.stamp().happenedBefore(change.stamp())).forEach(k -> write(past, k));
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

	private static void write ( Map<TodoId, Todo> todos, TodoChange change ) {
		OperationStamp stamp = change.stamp();
		switch ( change ) {
			case TodoAdded e -> todos.putIfAbsent(e.todoId(), new Todo(e.todoId(),
					MultiValueRegister.<String>empty().write(stamp, e.title()),
					MultiValueRegister.<TodoStatus>empty().write(stamp, TodoStatus.PLANNED), false));
			case TodoRenamed e -> writeTitle(todos, e, e.title());
			case TodoTitleSettled e -> writeTitle(todos, e, e.title());
			case TodoMarkedPlanned e -> writeStatus(todos, e, TodoStatus.PLANNED);
			case TodoMarkedOngoing e -> writeStatus(todos, e, TodoStatus.ONGOING);
			case TodoMarkedDone e -> writeStatus(todos, e, TodoStatus.DONE);
			case TodoDropped e -> writeStatus(todos, e, TodoStatus.DROPPED);
			case TodoChangeReviewed e -> { }
		}
	}

	private static void writeTitle ( Map<TodoId, Todo> todos, TodoChange change, String title ) {
		todos.computeIfPresent(change.todoId(), (id, t) -> new Todo(id, t.title().write(change.stamp(), title), t.status(), false));
	}

	private static void writeStatus ( Map<TodoId, Todo> todos, TodoChange change, TodoStatus status ) {
		todos.computeIfPresent(change.todoId(), (id, t) -> new Todo(id, t.title(), t.status().write(change.stamp(), status), false));
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
