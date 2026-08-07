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

import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Provides the execution context for automation handlers.
 * <p>
 * This interface exposes the capabilities available to an {@link Automation} when processing
 * todo items. Automations can execute commands and provide events to the system through
 * this context.
 * <p>
 * It deliberately offers both event destinations side by side — outbound through
 * {@code execute(OutboundCommand)}, domain through {@code event(...)} — because "record the fact and
 * publish it" is composed here, not inside any single command (a command appends to exactly one
 * stream). The composition is non-atomic and made safe by ordering plus idempotency keys, which is
 * easy to get subtly wrong by hand, so {@link #publishAndRecord(OutboundCommand, Object, String)}
 * does it correctly by construction — reach for it before composing the two calls yourself. See
 * {@link org.sliceworkz.eventmodeling.commands.OutboundCommand} and the project documentation.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of outbound events that can be published
 */
public interface AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends CommandExecutionCapability<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE>, ProvidedEventCapability<DOMAIN_EVENT_TYPE> {

	// TODO should we allow ProvidedEventsCapability? or only CommandExecution?  also Aggregates?

	/**
	 * Publishes through an {@link OutboundCommand} and records the fact as a domain event, composed
	 * the one safe way: <strong>outbound first, domain second, both under keys derived from the item.</strong>
	 * <p>
	 * The ordering is load-bearing. Only the domain event makes the todo list drop the item, so a
	 * crash between the two appends leaves the item outstanding and the retry re-runs both halves —
	 * the outbound half de-duplicates on its key, and the domain event then lands. Composed the other
	 * way round the publication is lost for good: the domain event completes the item, and nothing
	 * ever retries the outbound append.
	 * <p>
	 * The keys are {@code itemKey + "/outbound"} and {@code itemKey + "/domain"} — two keys because
	 * they are scoped per stream and guard two different appends. {@code itemKey} must therefore be
	 * stable per todo item (derive it from the item, never from the attempt), and unique among the
	 * automation's items — an id from the item is the natural choice, e.g. {@code "order/" + orderId}.
	 * <p>
	 * Returns the domain event's reference — what {@link Automation#handle} should return, since that
	 * is the event the todo list must catch up past. An empty return means the domain event was
	 * already recorded under this key (the item is being re-handled, and both halves de-duplicated),
	 * which for an automation is success: the work is done, and the todo list drops the item once the
	 * original event is projected.
	 *
	 * @param outboundCommand the command publishing for this item; executed under {@code itemKey + "/outbound"},
	 *        so it must accept an externally provided idempotency key and raise a single outbound event
	 *        (or key its events itself)
	 * @param domainEvent the domain event recording that the publication happened
	 * @param itemKey stable, item-derived key prefix for both appends; must not be null or blank
	 * @return the reference of the recorded domain event, or empty if it had already been recorded
	 */
	default Optional<EventReference> publishAndRecord ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> outboundCommand, DOMAIN_EVENT_TYPE domainEvent, String itemKey ) {
		return publishAndRecord(outboundCommand, domainEvent, Tags.none(), itemKey);
	}

	/**
	 * As {@link #publishAndRecord(OutboundCommand, Object, String)}, with tags on the domain event —
	 * which is where they usually matter, since the todo list projecting the item away may select by
	 * them.
	 */
	default Optional<EventReference> publishAndRecord ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> outboundCommand, DOMAIN_EVENT_TYPE domainEvent, Tags domainEventTags, String itemKey ) {
		if ( itemKey == null || itemKey.isBlank() ) {
			throw new IllegalArgumentException(
					"publishAndRecord needs a stable, item-derived key to make its two appends idempotent - derive it from the todo item (e.g. \"order/\" + orderId), never from the attempt");
		}
		execute(outboundCommand, itemKey + "/outbound");
		return event(domainEvent, domainEventTags, itemKey + "/domain");
	}

}