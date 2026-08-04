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

/**
 * What an automation wants done with a todo item whose {@link Automation#handle} threw.
 * <p>
 * Returned from {@link Automation#onFailure}. <strong>All three retry the item</strong> — a todo list is
 * projected from events, so an item that was not handled is still outstanding and is offered again; the
 * framework has nothing to drop it from and no way to remember that it tried. What is decided here is only
 * what happens to <em>the rest of the work</em> while this item waits its turn again:
 * <table border="1">
 *   <caption>the three answers</caption>
 *   <tr><th></th><th>this item</th><th>the items behind it</th></tr>
 *   <tr><td>{@link #RETRY_ITEM}</td><td>on the next batch</td><td>wait for it</td></tr>
 *   <tr><td>{@link #CONTINUE_AND_RETRY_ITEM_LATER}</td><td>on a later batch</td><td>handled now, ahead of it</td></tr>
 *   <tr><td>{@link #STOP_AUTOMATION}</td><td>after a restart</td><td>wait for a human</td></tr>
 * </table>
 * There is deliberately no "try again right now" here. A failure worth retrying within milliseconds is
 * almost always about the call the handler made rather than about the todo item, so it belongs inside
 * {@code handle}, where the code knows what it just attempted. What the framework can usefully do is back
 * the whole batch off, which is what happens between batches anyway — and if the failure was a conflict
 * with another writer, the todo list has moved and the next batch starts immediately rather than waiting.
 *
 * @see Automation#onFailure
 */
public enum AutomationFailureAction {

	/**
	 * Abandon the rest of this batch, keeping the automation running. The batch is attempted again from
	 * the front on the next round, so this item is retried before anything behind it.
	 * <p>
	 * The default, because it is the answer that assumes nothing: it holds the order the todo list asked
	 * for without needing to know whether the items are related. The cost is that a genuinely poison item
	 * at the head of the list holds up everything behind it, for as long as it keeps failing — an
	 * automation in that state is running and making no progress, so
	 * {@link AutomationStatus#itemsFailed} climbing with nothing handled is what it looks like from
	 * outside. The fixes are the two ordinary ones: {@link #CONTINUE_AND_RETRY_ITEM_LATER} where items are
	 * independent, or an {@link Automation#onFailure} that records the failure as an event its todo list
	 * projects, which takes the item out of the way for good.
	 */
	RETRY_ITEM,

	/**
	 * Carry on with the rest of this batch, and let this item come round on a later one.
	 * <p>
	 * The item stays outstanding on the todo list, so nothing is lost — but the items behind it are
	 * handled first, which means giving up the order {@code streamItems} returned them in. This is the
	 * only action that lets work overtake, which is why it is not the default. Choose it when the items
	 * are genuinely independent of one another: it is what keeps one poison item from holding up
	 * everything behind it. Where they are not independent, letting work overtake a failure is how a queue
	 * quietly produces wrong results.
	 */
	CONTINUE_AND_RETRY_ITEM_LATER,

	/**
	 * Stop the automation altogether until something restarts it. The failure is taken to mean that
	 * continuing at all is unsafe.
	 * <p>
	 * Nothing restarts the processor on its own; an operator does, through
	 * {@link AutomationAdminCapability#restartAutomation}, and a
	 * {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.AutomationStopped} is raised
	 * so they can see that they need to. Use it only where a human is genuinely meant to intervene.
	 */
	STOP_AUTOMATION

}
