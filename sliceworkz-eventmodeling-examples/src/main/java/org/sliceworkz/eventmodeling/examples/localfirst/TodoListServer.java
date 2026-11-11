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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Execution;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoId;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoStatus;
import org.sliceworkz.eventmodeling.examples.localfirst.features.resolvetodoconflict.ResolveTodoConflictCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todoconflicts.TodoConflictsReadModel.TodoConflict;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.VectorClock;
import org.sliceworkz.eventmodeling.examples.localfirst.features.addtodo.AddTodoCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.droptodo.DropTodoCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.marktododone.MarkTodoDoneCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.marktodoongoing.MarkTodoOngoingCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.marktodoplanned.MarkTodoPlannedCommand;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicationlog.ReplicationLogReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todolist.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.localfirst.features.todolist.TodoListReadModel.TodoView;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * One replica of the to-do list: a {@link TodoList} bounded context on an event store of its own.
 *
 * <p><b>Local first.</b> A user's command is decided on this replica's store alone and is done the moment
 * it returns; no other replica is asked, so it works just as well with every other replica unreachable.
 *
 * <p><b>Replication.</b> The domain events are the operations of the CRDT. {@link #operationsMissingAt}
 * answers what a peer lacks, given its vector clock; {@link #receive} takes a peer's operations in. An
 * operation from elsewhere is a fact already decided, so it is appended as it is through
 * {@code event(...)} (the framework's capability for facts settled before they reach the context), under
 * its operation id as idempotency key: the store itself makes a duplicate delivery a no-op. An operation
 * arriving before what it was decided on waits in a buffer until it can be delivered causally.
 */
public final class TodoListServer implements AutoCloseable {

	private final ReplicaId replica;
	private final EventStorage storage;
	private final TodoList context;
	private final Map<String, TodoEvent> waiting = new LinkedHashMap<>();

	public TodoListServer ( ReplicaId replica ) {
		this.replica = replica;
		this.storage = InMemoryEventStorage.newBuilder().build();
		this.context = BoundedContext.newBuilder(TodoList.class)
				.name("todolist")
				.eventStorage(storage)
				.instance(InstanceFactory.determine("todolist", replica.value()))
				.features().rootPackage(TodoListServer.class.getPackage()).done()
				.build();
		this.context.start();
	}

	public static TodoListServer named ( String replica ) {
		return new TodoListServer(ReplicaId.of(replica));
	}

	public ReplicaId replica ( ) {
		return replica;
	}

	// ------------------------------------------------------------------------------------- commands

	public TodoId addTodo ( String title ) {
		return context.execute(new AddTodoCommand(replica, title), Execution.retrying()).response();
	}

	public void markTodoPlanned ( TodoId todoId ) {
		context.execute(new MarkTodoPlannedCommand(replica, todoId), Execution.retrying());
	}

	public void markTodoOngoing ( TodoId todoId ) {
		context.execute(new MarkTodoOngoingCommand(replica, todoId), Execution.retrying());
	}

	public void markTodoDone ( TodoId todoId ) {
		context.execute(new MarkTodoDoneCommand(replica, todoId), Execution.retrying());
	}

	public void dropTodo ( TodoId todoId ) {
		context.execute(new DropTodoCommand(replica, todoId), Execution.retrying());
	}

	public void resolveConflict ( TodoId todoId, TodoStatus chosen ) {
		context.execute(new ResolveTodoConflictCommand(replica, todoId, chosen), Execution.retrying());
	}

	// ------------------------------------------------------------------------------------- reads

	/** The to-dos waiting for someone to resolve a conflict, as far as this replica knows. */
	public List<TodoConflict> conflicts ( ) {
		TodoConflictsReadModel conflicts = context.read(TodoConflictsReadModel.class);
		return conflicts.conflicts();
	}

	/** The to-dos not dropped (those in conflict included), as this replica knows them now. */
	public List<TodoView> todos ( ) {
		TodoListReadModel list = context.read(TodoListReadModel.class);
		return list.todos();
	}

	/** Every to-do, dropped ones included: the state replicas compare to tell they converged. */
	public List<TodoView> state ( ) {
		TodoListReadModel list = context.read(TodoListReadModel.class);
		return list.everything();
	}

	public TodoView todo ( TodoId todoId ) {
		return state().stream().filter(t -> t.todoId().equals(todoId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("replica " + replica + " does not know to-do " + todoId.value()));
	}

	// ------------------------------------------------------------------------------------- replication

	public VectorClock versionVector ( ) {
		return log().versionVector();
	}

	/** The operations a peer that has seen {@code peer} lacks, in an order it can take them in. */
	public List<TodoEvent> operationsMissingAt ( VectorClock peer ) {
		return log().missingAt(peer);
	}

	/** Every operation this replica holds, in the order it stored them. */
	public List<TodoEvent> operations ( ) {
		return log().operations();
	}

	/**
	 * Takes operations of other replicas in, in whatever order and however often they arrive. Each is
	 * delivered once everything it was decided on is here; the rest waits. Answers how many were delivered.
	 */
	public synchronized int receive ( Collection<? extends TodoEvent> operations ) {
		VectorClock local = versionVector();
		for ( TodoEvent operation : operations ) {
			if ( !operation.stamp().seenBy(local) ) {
				waiting.putIfAbsent(operation.stamp().operationId(), operation);
			}
		}
		int delivered = 0;
		boolean progress = true;
		while ( progress ) {
			progress = false;
			for ( TodoEvent operation : new ArrayList<>(waiting.values()) ) {
				if ( operation.stamp().seenBy(local) ) {
					waiting.remove(operation.stamp().operationId());
				} else if ( operation.stamp().deliverableAt(local) ) {
					String key = operation.stamp().operationId();
					context.event(EphemeralEvent.of(operation, TodoListDomain.tagsOf(operation.todoId(), operation.stamp().origin()))
							.withIdempotencyKey(key));
					local = local.mergedWith(operation.stamp().clock());
					waiting.remove(key);
					delivered++;
					progress = true;
				}
			}
		}
		return delivered;
	}

	/** Operations received but not deliverable yet, because something they depend on has not arrived. */
	public synchronized int waitingOperations ( ) {
		return waiting.size();
	}

	private ReplicationLogReadModel log ( ) {
		return context.read(ReplicationLogReadModel.class);
	}

	@Override
	public void close ( ) {
		context.terminate();
		storage.close();
	}

	@Override
	public String toString ( ) {
		return "replica " + replica + " " + versionVector();
	}

}
