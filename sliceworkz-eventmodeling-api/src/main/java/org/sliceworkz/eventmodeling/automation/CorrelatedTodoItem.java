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

import org.sliceworkz.eventmodeling.events.Tracing;

/**
 * Opt-in contract for a todo item that knows which flow it belongs to, so the events its handling
 * raises are correlated with the events that caused it.
 * <p>
 * A correlation id names a flow — command → domain event → todo list → automation → outbound event —
 * and is reused, never re-minted, across every step (see {@link Tracing#correlationId(String)}). An
 * automation is the one step the framework cannot bridge by itself: the todo item is an arbitrary
 * user type projected out of the event history, and nothing links it back to the event that put it on
 * the list. This interface is that link. The todo list captures the id where it is visible — in its
 * {@code when(Event)} the triggering event's tags are at hand, so
 * {@code Tracing.readFrom(event).correlationId()} — and carries it on the item:
 *
 * <pre>{@code
 * public record PaymentToExecute ( String paymentId, long amount, String correlationId )
 *         implements CorrelatedTodoItem { }
 *
 * // in the todo list read model:
 * public void when ( Event<PaymentEvent> event ) {
 *     if ( event.data() instanceof PaymentRequested requested ) {
 *         items.add(new PaymentToExecute(requested.paymentId(), requested.amount(),
 *                 Tracing.readFrom(event).correlationId()));
 *     }
 * }
 * }</pre>
 *
 * When an item implements this interface and reports a non-blank id, the framework hands
 * {@code Automation.handle} (and {@code onFailure}) a context whose tracing carries that id, so
 * everything raised through it — {@code execute(...)}, {@code event(...)},
 * {@code publishAndRecord(...)} — is stamped with the item's flow, per item, without the automation
 * doing anything. An item that does not implement this interface, or reports {@code null} or a blank
 * id, keeps today's behaviour: the batch-level tracing, whose correlation id names the automation
 * run rather than any one flow.
 */
public interface CorrelatedTodoItem {

	/**
	 * The correlation id of the flow this item belongs to — normally the id read off the event that
	 * put the item on the todo list, via {@code Tracing.readFrom(event).correlationId()}.
	 *
	 * @return the flow's correlation id, or {@code null} when unknown (e.g. the triggering event was
	 *         written before correlation ids existed)
	 */
	String correlationId ( );

}
