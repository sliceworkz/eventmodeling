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

import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * What an automation wants done with a todo item whose {@link Automation#handle} threw.
 * <p>
 * Returned from {@link Automation#onFailure}. <strong>None of these drops the item.</strong> A todo list
 * is projected from events, so an item that was not handled is still outstanding and is offered again; the
 * framework has nothing to drop it from and no way to remember that it tried. What these actions decide is
 * only <em>when</em> the item comes back and <em>what else waits</em> in the meantime:
 * <table border="1">
 *   <caption>the four answers</caption>
 *   <tr><th></th><th>this item</th><th>the items behind it</th></tr>
 *   <tr><td>{@link #RETRY_NOW}</td><td>again, immediately</td><td>wait for it</td></tr>
 *   <tr><td>{@link #RETRY_LATER}</td><td>on a later batch</td><td>handled now, ahead of it</td></tr>
 *   <tr><td>{@link #STOP_BATCH}</td><td>on the next batch</td><td>wait for it</td></tr>
 *   <tr><td>{@link #STOP_AUTOMATION}</td><td>after a restart</td><td>wait for a human</td></tr>
 * </table>
 * The one thing to notice in that table is the second row: {@link #RETRY_LATER} is the only action that
 * lets work overtake, and it is therefore the only one that gives up the order {@code streamItems}
 * defined. That is why it is not the default, useful as it is.
 *
 * @see Automation#onFailure
 */
public enum AutomationFailureAction {

	/**
	 * Hand the same item to {@link Automation#handle} again straight away, a bounded number of times with
	 * a short backoff between attempts, and then treat it as {@link #STOP_BATCH}.
	 * <p>
	 * For failures a second attempt can plausibly clear on its own, such as a dropped database connection.
	 * Retrying in place keeps the order, so nothing overtakes the failing item while it is being retried.
	 */
	RETRY_NOW,

	/**
	 * Leave this item for a later batch and carry on with the rest of this one.
	 * <p>
	 * The item stays outstanding on the todo list and comes round again, so nothing is lost — but the
	 * items behind it are handled first, which means giving up the order {@code streamItems} returned
	 * them in. Choose it when the items are genuinely independent of one another, where it is what keeps
	 * one poison item from holding up everything behind it. Where they are not independent, letting work
	 * overtake a failure is how a queue quietly produces wrong results, which is why the default is
	 * {@link #STOP_BATCH} instead.
	 */
	RETRY_LATER,

	/**
	 * Abandon the rest of this batch, keeping the automation running. The batch is attempted again from
	 * the front on the next round, so this item is retried before anything behind it.
	 * <p>
	 * The default, because it is the answer that assumes nothing: it holds the order the todo list asked
	 * for without needing to know whether the items are related. The cost is that a genuinely poison item
	 * at the head of the list holds up everything behind it, for as long as it keeps failing — an
	 * automation in that state is running and making no progress, so
	 * {@link AutomationStatus#itemsFailed} climbing with nothing handled is what it looks like from
	 * outside. The fixes are the two ordinary ones: {@link #RETRY_LATER} where items are independent, or
	 * an {@link Automation#onFailure} that records the failure as an event its todo list projects, which
	 * takes the item out of the way for good.
	 */
	STOP_BATCH,

	/**
	 * Stop the automation altogether until something restarts it. The failure is taken to mean that
	 * continuing at all is unsafe.
	 * <p>
	 * Nothing restarts the processor on its own; an operator does, through
	 * {@link AutomationAdminCapability#restartAutomation}, and a
	 * {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.AutomationStopped} is raised
	 * so they can see that they need to. Use it only where a human is genuinely meant to intervene.
	 */
	STOP_AUTOMATION;

	/**
	 * The action applied when an automation does not override {@link Automation#onFailure}: retry a
	 * storage failure in place, and stop the batch for anything else.
	 * <p>
	 * <strong>The default never lets work overtake a failure.</strong> {@code streamItems} defines the
	 * order items are handled in and the framework honours it, so it would be odd for the framework to
	 * abandon that order the moment something goes wrong — and it cannot tell whether the items behind a
	 * failing one depend on it. Of the two ways to be wrong here, holding up independent items is a stall
	 * that shows up in {@link AutomationStatus} and clears when the cause is dealt with, while overtaking
	 * dependent ones produces incorrect results and reports nothing at all.
	 * <p>
	 * The cause chain is scanned, so a failure the framework has wrapped is classified on what actually
	 * went wrong: an {@link EventStorageException} is transient and gets {@link #RETRY_NOW}, which retries
	 * without giving up the order. Everything else — a poison payload, an optimistic-locking conflict, a
	 * bug in the handler — gets {@link #STOP_BATCH}, leaving the automation running and the item at the
	 * front of the queue.
	 *
	 * @param cause the throwable that escaped {@link Automation#handle}
	 * @return the default action for that cause, never null
	 */
	public static AutomationFailureAction defaultFor ( Throwable cause ) {
		for ( Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause() ) {
			if ( t instanceof EventStorageException ) {
				return RETRY_NOW;
			}
		}
		return STOP_BATCH;
	}

}
