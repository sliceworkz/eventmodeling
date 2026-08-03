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
package org.sliceworkz.eventmodeling.automation;

import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * What an automation wants done with a todo item whose {@link Automation#handle} threw.
 * <p>
 * Returned from {@link Automation#onFailure}. Nothing here loses the item: a todo list is projected
 * from events, so an item that is skipped or left behind is still outstanding and is offered again on
 * a later round. What these actions decide is how much of the current batch — and of the automation —
 * a single failing item takes with it.
 *
 * @see Automation#onFailure
 */
public enum AutomationFailureAction {

	/**
	 * Hand the same item to {@link Automation#handle} again straight away, a bounded number of times
	 * with a short backoff between attempts, before falling back to {@link #SKIP_ITEM}. For failures
	 * that a second attempt can plausibly clear on its own, such as a dropped database connection.
	 */
	RETRY_ITEM,

	/**
	 * Leave this item alone and carry on with the rest of the batch. The item stays outstanding on the
	 * todo list and comes round again on a later batch, so this is the right answer whenever the items
	 * are independent of one another — including for a poison item, which no number of retries will fix
	 * but which must not hold up the items behind it.
	 */
	SKIP_ITEM,

	/**
	 * Abandon the rest of this batch, keeping the automation running. For work that has to be handled
	 * in order: the items behind the failing one are not independent of it, so they wait for it rather
	 * than proceeding without it. The batch is attempted again from the front on the next round.
	 */
	STOP_BATCH,

	/**
	 * Stop the automation altogether until the bounded context is started again. The failure is taken to
	 * mean that continuing at all is unsafe. Nothing restarts the processor on its own, so use this only
	 * where a human is meant to intervene.
	 */
	STOP_AUTOMATION;

	/**
	 * The action applied when an automation does not override {@link Automation#onFailure}.
	 * <p>
	 * The cause chain is scanned, so a failure the framework has wrapped is classified on what actually
	 * went wrong:
	 * <ul>
	 *   <li>{@link OptimisticLockingException} → {@link #SKIP_ITEM}. Not really a failure: another writer
	 *       moved the consistency boundary this item was decided on, which usually means the todo list is
	 *       about to change under us. Re-reading it on the next round is the repair, so there is nothing
	 *       to retry here.</li>
	 *   <li>{@link EventStorageException} → {@link #RETRY_ITEM}, as the storage may simply be briefly
	 *       unavailable.</li>
	 *   <li>{@link EventSerializationException}, {@link EventDeserializationException} →
	 *       {@link #SKIP_ITEM}: a payload that cannot be converted fails identically on every attempt.</li>
	 *   <li>anything else → {@link #SKIP_ITEM}, so that one item's bug does not retire the automation.</li>
	 * </ul>
	 *
	 * @param cause the throwable that escaped {@link Automation#handle}
	 * @return the default action for that cause, never null
	 */
	public static AutomationFailureAction defaultFor ( Throwable cause ) {
		for ( Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause() ) {
			if ( t instanceof OptimisticLockingException ) {
				return SKIP_ITEM;
			}
			if ( t instanceof EventSerializationException || t instanceof EventDeserializationException ) {
				return SKIP_ITEM;
			}
			if ( t instanceof EventStorageException ) {
				return RETRY_ITEM;
			}
		}
		return SKIP_ITEM;
	}

}
