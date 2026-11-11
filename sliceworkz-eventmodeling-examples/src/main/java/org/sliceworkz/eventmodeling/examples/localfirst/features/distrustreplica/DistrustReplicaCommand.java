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
package org.sliceworkz.eventmodeling.examples.localfirst.features.distrustreplica;

import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent;
import org.sliceworkz.eventmodeling.examples.localfirst.TodoListDomain.TodoEvent.ReplicaDistrusted;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.OperationStamp;
import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;
import org.sliceworkz.eventmodeling.examples.localfirst.features.replicastate.ReplicaStateDecisionModel;

/**
 * The security office declares a replica taken over after its operation number {@code trustedUpTo}.
 * Only the security office may: every replica ignores a declaration from anywhere else, and this command
 * refuses to make one. A cut only ever moves earlier, which is what lets declarations converge.
 */
public record DistrustReplicaCommand ( ReplicaId replica, ReplicaId distrusted, long trustedUpTo, String reason ) implements Command<TodoEvent> {

	@Override
	public void execute ( CommandContext<TodoEvent, TodoEvent> context ) {
		var state = new ReplicaStateDecisionModel();
		var result = context.decisionModels(state);

		BusinessException.when(!replica.equals(TodoListDomain.SECURITY_OFFICE), "Only the security office can distrust a replica");
		BusinessException.when(distrusted.equals(TodoListDomain.SECURITY_OFFICE), "The security office cannot distrust itself");
		BusinessException.when(trustedUpTo < 0, "A replica is trusted up to an operation number of 0 or more");
		BusinessException.when(reason == null || reason.isBlank(), "Distrusting a replica needs a reason");
		var current = state.distrustedAfter(distrusted);
		BusinessException.when(current.isPresent() && current.get() <= trustedUpTo,
				"Replica " + distrusted + " is already distrusted after operation " + current.orElse(0L));

		OperationStamp stamp = state.nextStamp(replica);
		var declaration = new ReplicaDistrusted(distrusted, trustedUpTo, reason.strip(), stamp);
		result.raiseEvent(declaration, TodoListDomain.tagsOf(declaration), stamp.operationId());
	}

}
