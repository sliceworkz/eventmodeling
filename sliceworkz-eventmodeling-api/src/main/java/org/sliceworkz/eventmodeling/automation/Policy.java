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

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A policy, in the event storming sense: <em>whenever</em> a domain event happens, issue a command.
 * <p>
 * The simpler of the two forms an automation takes. An {@link Automation} works through a to-do list —
 * a read model of what is outstanding — and issues a command per item; a policy reacts to the domain
 * event itself, with nothing in between:
 * <pre>
 * domain event ─► Policy ─► command ─► domain events
 * </pre>
 * Take a policy where one fact simply leads to one action — {@code Session Cancelled} → cancel the
 * session's subscriptions — and a to-do list would only hold one item per occurrence of that event until
 * the command's own event takes it off again. Take an automation with a to-do list where the work needs
 * anything a policy cannot give it: a deadline or the passing of time, items gathered from several facts,
 * several changes conflated into one action, a port to call (a payment gateway, a mail server), or an item
 * that can be put aside and retried later while the work behind it goes on.
 * <p>
 * <strong>What a policy does is select and map, nothing more.</strong> {@link #react} looks at one domain
 * event and answers the command to issue for it, or nothing when the event is not one it acts on. It
 * decides nothing — the command decides, on its own decision models, inside its own consistency boundary,
 * exactly as it would for a user — and it calls out to nothing: a policy is handed no context, so it has
 * no ports, reads no read models and appends nothing itself. Whatever the command needs beyond the event's
 * data it reads through its decision models.
 * <p>
 * <strong>How it runs.</strong> A policy is projected over the domain stream on a single elected leader of
 * the deployment, bookmarked there like a publisher, one domain event at a time in stream order. It belongs
 * to the slice of the command it issues — an automation slice — and is part of that slice's automation
 * aspect: register it from {@code configureAutomation} with {@code builder.policy(...)}, which makes two
 * choices mandatory, because nothing else could make them for you:
 * <ul>
 * <li><b>where it starts</b> when it is first deployed — {@code fromNowOn()} or {@code fromTheBeginning()}.
 *     A policy has no to-do list remembering what is done, so deployed over an existing history it would
 *     otherwise issue its command for every matching event ever recorded</li>
 * <li><b>what a business rejection does</b> — {@code stallOnRejection()} or {@code skipRejections()}. The
 *     command's "no" is either a sign that something is wrong, to be looked at before anything behind it
 *     proceeds, or an ordinary answer, to be recorded and moved past</li>
 * </ul>
 * Every other failure stalls the policy: it retries the event with backoff, holding the order, and a
 * permanent one retires it for an operator to restart.
 * <p>
 * <strong>Reacting is effectively once per domain event.</strong> When the event is handed over again — a
 * crash between the append and the bookmark, a failover overlap — the reaction stands: everything the command
 * raised is tagged with the policy as actor and the event as cause, and finding one such event the policy does
 * not react again; and the command is executed with an idempotency key derived from the policy's name and the
 * event's id ({@code policy:<name>@<event id>}), which covers two leaders reacting at once. A command issued
 * by a policy is nonetheless written to be idempotent by its own rules as well — run again for the same fact,
 * it raises nothing, which is what covers a reaction that raised nothing the first time — and one creating an
 * entity derives the new entity's id from the triggering event, as every command issued by an automation does.
 * <p>
 * <strong>The flow continues.</strong> The command runs under the triggering event's correlation id, with
 * the policy as its actor and {@code policy} as its channel, and every event it raises is tagged with the
 * id of the event it was raised for ({@code x-caused-by}).
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface Policy<DOMAIN_EVENT_TYPE> {

	/**
	 * The domain events this policy reacts to: one event type, or several that lead to the same reaction —
	 * the variants of one fact ({@code Cost Approved}, {@code Cost Partially Approved}, {@code Cost Declined})
	 * leading to one command.
	 *
	 * @return the query selecting them from the domain stream
	 */
	EventQuery eventQuery ( );

	/**
	 * The command to issue for one domain event, or nothing when this event is not one the policy acts on.
	 * Called on the policy's processor thread, once per matching domain event, in stream order — and again
	 * for the same event after a crash or a failover, see above. Map the event onto the command and do
	 * nothing else: no decision, no I/O, no state kept between calls.
	 *
	 * @param event the domain event, as stored
	 * @return the command to execute, or empty to let the event pass
	 */
	Optional<Command<DOMAIN_EVENT_TYPE>> react ( Event<DOMAIN_EVENT_TYPE> event );

	/**
	 * The name this policy is known by: its bookmark, its processor, its lease, and the namespace of the
	 * idempotency keys of every command it issues. All of these are durable, so the name must be stable:
	 * renaming the class gives the policy a fresh bookmark and fresh keys, which — depending on where it was
	 * registered to start — either reacts to the whole history again or skips what happened in between.
	 * Override this to keep the old name when the class is renamed.
	 *
	 * @return the policy's name; the class' simple name by default
	 */
	default String policyName ( ) {
		return getClass().getSimpleName();
	}

}
