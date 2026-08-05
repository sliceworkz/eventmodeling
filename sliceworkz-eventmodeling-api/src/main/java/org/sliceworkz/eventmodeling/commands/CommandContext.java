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
package org.sliceworkz.eventmodeling.commands;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;

public interface CommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	/**
	 * Projects a live read model and hands it to the command, for auxiliary lookups only.
	 * <p>
	 * <strong>A read done this way is not part of the command's consistency boundary.</strong> The
	 * boundary the command appends under is built from its decision models alone, so the events this
	 * read model was projected from are not covered by the optimistic-locking check: one of them can be
	 * superseded between this read and the append, and the append still succeeds. A command that reads
	 * nothing but this and then calls {@link #noDecisionModels()} appends with no consistency check at
	 * all.
	 * <p>
	 * So do not decide on what this returns. Anything a raised event depends on belongs in a
	 * {@link DecisionModel} passed to {@link #decisionModels(DecisionModel...)}, which is what puts it
	 * inside the boundary.
	 */
	<T> T read ( Class<? extends ReadModel<? extends CONSUMED_EVENT_TYPE>> readModelClass, Object... constructorParams);
	
	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> noDecisionModels ( );
	
	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> decisionModels ( @SuppressWarnings("unchecked") DecisionModel<CONSUMED_EVENT_TYPE>... decisionModels );

}
