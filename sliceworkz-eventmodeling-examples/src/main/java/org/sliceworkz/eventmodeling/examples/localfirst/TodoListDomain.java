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
import java.util.Set;

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
		 * Whether concurrent status changes are a <b>business conflict</b>: two offices took the to-do in
		 * different directions without knowing about each other, and a person has to decide which one
		 * stands. Until then the to-do has no agreed status and nothing else may be done with it.
		 *
		 * <p>A drop is not a conflict: whoever dropped it meant it gone, whatever else happened to it, so a
		 * drop settles concurrent changes on its own ({@link #CONCURRENT_PRIORITY}).
		 */
		public static boolean conflicting ( Set<TodoStatus> concurrent ) {
			return concurrent.size() > 1 && !concurrent.contains(DROPPED);
		}

		/**
		 * How concurrent status changes that are no conflict resolve: drop wins. Applied to a conflict, the
		 * order only decides what the to-do would show if nobody looked at the conflict; it never does,
		 * since a conflict has no agreed status. Any total order works, as long as every replica uses the
		 * same one.
		 */
		public static final Comparator<TodoStatus> CONCURRENT_PRIORITY = Comparator.comparingInt(status -> switch ( status ) {
			case PLANNED -> 0;
			case ONGOING -> 1;
			case DONE -> 2;
			case DROPPED -> 3;
		});
	}

	sealed interface TodoEvent {

		TodoId todoId ( );

		OperationStamp stamp ( );

		/** A new to-do, planned. Its id is minted by the replica that adds it, so two adds never collide. */
		record TodoAdded ( TodoId todoId, String title, OperationStamp stamp ) implements TodoEvent { }

		record TodoMarkedPlanned ( TodoId todoId, OperationStamp stamp ) implements TodoEvent { }

		record TodoMarkedOngoing ( TodoId todoId, OperationStamp stamp ) implements TodoEvent { }

		record TodoMarkedDone ( TodoId todoId, OperationStamp stamp ) implements TodoEvent { }

		record TodoDropped ( TodoId todoId, OperationStamp stamp ) implements TodoEvent { }

		/**
		 * Someone who saw the conflicting changes chose the status that stands. Its clock covers every
		 * change it settles, so it replaces them all on every replica it reaches.
		 */
		record TodoConflictResolved ( TodoId todoId, TodoStatus status, OperationStamp stamp ) implements TodoEvent { }

	}

	/** The tags of an operation: the to-do it is about and the replica that decided it. */
	static Tags tagsOf ( TodoId todoId, ReplicaId replica ) {
		return Tags.of(TODO.tag(todoId), REPLICA.tag(replica));
	}

	/** The status every operation writes into a to-do's status register. */
	static TodoStatus statusWrittenBy ( TodoEvent event ) {
		return switch ( event ) {
			case TodoEvent.TodoAdded ignored -> TodoStatus.PLANNED;
			case TodoEvent.TodoMarkedPlanned ignored -> TodoStatus.PLANNED;
			case TodoEvent.TodoMarkedOngoing ignored -> TodoStatus.ONGOING;
			case TodoEvent.TodoMarkedDone ignored -> TodoStatus.DONE;
			case TodoEvent.TodoDropped ignored -> TodoStatus.DROPPED;
			case TodoEvent.TodoConflictResolved resolved -> resolved.status();
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
