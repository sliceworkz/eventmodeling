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

import java.util.Comparator;

import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The to-do list, local first: every replica decides on its own and its domain events are the operations
 * of an operation-based CRDT.
 *
 * <p>Each domain event carries an {@link OperationStamp}. The command decides on the replica's local
 * state (the <i>prepare</i> phase of an op-based CRDT) and raises an event; that event is what travels to
 * the other replicas and is applied there as it is (the <i>effect</i>), never re-decided. Re-deciding a
 * command on another replica could reach another decision, and the replicas would diverge.
 */
public interface TodoListDomain {

	record TodoId ( String value ) implements EntityId { }

	Entity<TodoId> TODO = Entity.of("todo", TodoId::new);

	/** The replica that decided an operation; an event is tagged with it. */
	Entity<ReplicaId> REPLICA = Entity.of("replica", ReplicaId::new);

	/** Where a to-do stands. {@code DROPPED} is a tombstone: a dropped to-do is never forgotten, it is hidden. */
	enum TodoStatus {
		PLANNED, ONGOING, DONE, DROPPED;

		/**
		 * How concurrent status changes resolve, automatically and alike on every replica: <b>a drop wins,
		 * and otherwise the earliest state wins</b>. Whoever puts a to-do back on the plan, or keeps it
		 * ongoing, has a reason the office that finished it did not know about, so the step back counts
		 * for more than the step forward. A drop wins over everything: whoever dropped it meant it gone.
		 *
		 * <p>Any total order would converge, as long as every replica uses the same one; this one is the
		 * business decision. So concurrent status changes are never a conflict a person has to settle.
		 */
		public static final Comparator<TodoStatus> CONCURRENT_PRIORITY = Comparator.comparingInt(status -> switch ( status ) {
			case DONE -> 0;
			case ONGOING -> 1;
			case PLANNED -> 2;
			case DROPPED -> 3;
		});
	}

	/**
	 * The replica that may declare another replica compromised. A declaration from anywhere else counts for
	 * nothing, on every replica, so a taken-over office cannot turn the mechanism against the honest ones.
	 *
	 * <p>A proof of concept trusts the origin an operation claims. In earnest, that only holds with every
	 * operation signed by its office and chained by hash; see the README.
	 */
	ReplicaId SECURITY_OFFICE = ReplicaId.of("security");

	/** What a reviewer decided about an honest change made on input that turned out to be compromised. */
	enum Verdict { CONFIRMED, REVERTED }

	sealed interface TodoEvent {

		OperationStamp stamp ( );

		/** An operation on one to-do. */
		sealed interface TodoChange extends TodoEvent {
			TodoId todoId ( );
		}

		/** A new to-do, planned. Its id is minted by the replica that adds it, so two adds never collide. */
		record TodoAdded ( TodoId todoId, String title, OperationStamp stamp ) implements TodoChange { }

		record TodoRenamed ( TodoId todoId, String title, OperationStamp stamp ) implements TodoChange { }

		record TodoMarkedPlanned ( TodoId todoId, OperationStamp stamp ) implements TodoChange { }

		record TodoMarkedOngoing ( TodoId todoId, OperationStamp stamp ) implements TodoChange { }

		record TodoMarkedDone ( TodoId todoId, OperationStamp stamp ) implements TodoChange { }

		record TodoDropped ( TodoId todoId, OperationStamp stamp ) implements TodoChange { }

		/**
		 * Someone who saw the concurrent renames settled the title: one of them, or a new one. Its clock
		 * covers every rename it settles, so it replaces them all on every replica it reaches.
		 */
		record TodoTitleSettled ( TodoId todoId, String title, OperationStamp stamp ) implements TodoChange { }

		/**
		 * A reviewer decided about an honest change made on compromised input: it stands, or it is reverted,
		 * which leaves it out of the to-do list everywhere, exactly like a compromised operation.
		 */
		record TodoChangeReviewed ( TodoId todoId, String operationId, Verdict verdict, OperationStamp stamp ) implements TodoChange { }

		/**
		 * The security office declares that {@code replica} was taken over after its operation number
		 * {@code trustedUpTo}: every later operation of it counts for nothing. The cut is in CRDT coordinates,
		 * since event ids differ per replica and the compromised office's clock cannot be believed.
		 */
		record ReplicaDistrusted ( ReplicaId replica, long trustedUpTo, String reason, OperationStamp stamp ) implements TodoEvent { }

	}

	/** The tags of an operation: the to-do it is about and the replica that decided it. */
	static Tags tagsOf ( TodoId todoId, ReplicaId replica ) {
		return Tags.of(TODO.tag(todoId), REPLICA.tag(replica));
	}

	/** The tags of any operation, as its origin raised them. */
	static Tags tagsOf ( TodoEvent event ) {
		return switch ( event ) {
			case TodoEvent.TodoChange change -> tagsOf(change.todoId(), change.stamp().origin());
			case TodoEvent.ReplicaDistrusted distrust -> Tags.of(REPLICA.tag(distrust.stamp().origin()));
		};
	}

	sealed interface TodoListInboundEvent {

		/**
		 * Not used by this proof of concept, where replication calls the server directly. Over a real
		 * network, an operation of another replica would come in here and a translator would take it in.
		 */
		record OperationReceived ( TodoId todoId ) implements TodoListInboundEvent { }

	}

	sealed interface TodoListOutboundEvent {

		/** Not used by this proof of concept; a bounded context declares three event types even so. */
		record OperationShared ( TodoId todoId ) implements TodoListOutboundEvent { }

	}

}
