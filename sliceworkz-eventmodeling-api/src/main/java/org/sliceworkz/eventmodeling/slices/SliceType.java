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
package org.sliceworkz.eventmodeling.slices;

import java.util.Collection;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.MemberKind;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.SliceMember;

/**
 * Which of the four Event Modeling patterns a feature slice is, derived from what it is made of rather
 * than declared.
 * <p>
 * A slice's type is a statement about its parts: a command raising events is a state change, a read
 * model on its own is a state read, a to-do list worked by a processor is an automation, an external
 * event turned into a command is a translation. Declared by hand, that statement is one nobody checks,
 * and it drifts from the code the moment a slice gains or loses a part. So there is nothing to declare:
 * {@link #of(Collection)} reads the type off the components a slice registers on the builder in its
 * {@code configure...} methods, the same {@link SliceMember}s it announces on
 * {@code BoundedContextStarting}.
 * <p>
 * The rule is the one the Sliceworkz Modeler applies to a <em>modeled</em> slice, so a slice in the
 * code and the same slice in the model come out as the same type: {@link #derive(int, int, int, int, int, int)}
 * is that rule over the counts of the slice's elements — the modeler calls it for its modeled slices — and {@link #of(Collection)} maps the registered
 * components onto those elements:
 * <ul>
 * <li>a command and an aggregate are a command raising domain events</li>
 * <li>a read model is a read model</li>
 * <li>an automation is a command raised from a read model — the to-do list it works, which it counts
 *     as whether or not the same slice registers it</li>
 * <li>a policy is a command raised from a triggering domain event: the other form of an automation, with
 *     no to-do list between the event and the command</li>
 * <li>a translator is an inbound event and the command it is turned into</li>
 * <li>a publisher is a domain event mapped into an outbound event, and a dispatcher an outbound event</li>
 * <li>a read model registered only for a publisher of the same slice to read is part of that publication,
 *     and counts as nothing: the model doesn't show it, and it is no to-do list</li>
 * </ul>
 * A slice that publishes is therefore of the type its other parts give it: a state change or an automation
 * with an integration event linked to it stays one, also when its publisher reads a read model to build
 * the message. A slice that <em>only</em> publishes — no command, no
 * inbound event, the domain events it publishes for and the outbound event — is an automation: the
 * separate publishing slice several slices share when they publish the same integration event.
 * What the code cannot say is which events a command raises, so every command is taken to raise one.
 * <p>
 * Two consequences worth knowing:
 * <ul>
 * <li><b>Only what is registered counts.</b> A command is executed ad hoc and need not be registered,
 *     so a slice that only wires an endpoint in {@code startCommand} derives as {@link #UNDEFINED}.
 *     Declare its command with {@code builder.command(...)} — which is purely declarative — and it is a
 *     state change. A scheduler in {@code startAutomation} executing a command is, by its parts, a state
 *     change as well: nothing registered says a processor issues it.</li>
 * <li><b>A command beside a read model is an automation</b>, whether or not an {@code Automation} is
 *     registered, exactly as in the model: the read model is taken for the to-do list the command is
 *     issued from. A slice that is a state change and a state read at once — a command and the live
 *     read model its screen shows — is two slices in Event Modeling, and splitting it is what makes its
 *     type come out right.</li>
 * <li><b>Only what is deployed is registered.</b> A slice is configured only for the aspects its
 *     deployment runs, so an instance deploying commands alone sees an automation slice's command and
 *     not its automation, and an undeployed slice registers nothing. The type on a
 *     {@code BoundedContextEvent.FeatureSlice} is therefore the type of what this instance deployed of
 *     the slice; a reader combining several instances derives it again from the union of their members.</li>
 * </ul>
 */
public enum SliceType {

	/** A command raising domain events: trigger → command → event. */
	STATE_CHANGE,

	/** A read model projected from events: events → read model → UI/API. */
	STATE_READ,

	/**
	 * A processor issuing commands, in one of three forms. Driven by a to-do list: events → to-do list →
	 * processor → command → event. Driven by a triggering domain event, a policy: event → processor →
	 * command → event. And a slice that only publishes: domain events mapped into an outbound event, with
	 * no command of its own.
	 */
	AUTOMATION,

	/** An external event turned into a command: inbound event → processor → command → event. */
	TRANSLATION,

	/** Parts that fit more than one pattern, or none of them — a command next to a read model and an inbound event, say. */
	UNCLEAR,

	/** No parts at all: the slice registers nothing this derivation can see. */
	UNDEFINED;

	/**
	 * The type of a slice made of the given components.
	 *
	 * @param members what the slice registered; {@code null} or empty is {@link #UNDEFINED}
	 */
	public static SliceType of ( Collection<SliceMember> members ) {
		int commands = 0, readModels = 0, domainEvents = 0, triggering = 0, inbound = 0, outbound = 0;
		if ( members != null ) {
			for ( SliceMember member : members ) {
				MemberKind kind = member.kind();
				if ( kind == null ) {
					continue;
				}
				switch ( kind ) {
					case COMMAND, AGGREGATE -> { commands++; domainEvents++; }
					case READ_MODEL -> readModels++;
					case AUTOMATION -> { commands++; domainEvents++; readModels++; }
					// a policy is the command it issues and the domain event that triggers it, with no to-do list
					case POLICY -> { commands++; domainEvents++; triggering++; }
					case TRANSLATOR -> { commands++; domainEvents++; inbound++; }
					// a publisher is what the model shows as an integration event linked to the slice whose
					// domain event it publishes: that domain event and an outbound event, whatever else the slice is
					case PUBLISHER -> { domainEvents++; outbound++; }
					case DISPATCHER -> outbound++;
					// what a publisher reads to build its message is code only, as in the model: no to-do list
					case PUBLICATION_READ_MODEL -> { }
				}
			}
		}
		return derive(commands, readModels, domainEvents, triggering, inbound, outbound);
	}

	/**
	 * The Sliceworkz Modeler's rule, over the counts of a slice's elements, for a slice with no
	 * triggering domain event: {@link #derive(int, int, int, int, int, int)} with none.
	 */
	public static SliceType derive ( int commands, int readModels, int producedDomainEvents, int inboundEvents, int outboundEvents ) {
		return derive(commands, readModels, producedDomainEvents, 0, inboundEvents, outboundEvents);
	}

	/**
	 * The Sliceworkz Modeler's rule, over the counts of a slice's elements: exactly one pattern has to
	 * match for the slice to be of that type, none of the counts is {@link #UNDEFINED}, anything else is
	 * {@link #UNCLEAR}.
	 * <p>
	 * A <em>triggering</em> domain event is one a command is issued for, without a to-do list in between —
	 * the event of a policy. It is counted apart from the events the slice's commands raise, because the two
	 * say opposite things: a command beside the events it raises is a state change, a command beside the
	 * event that triggers it is an automation. A triggering event is to a policy what an inbound event is to
	 * a translation.
	 *
	 * @param commands the slice's commands
	 * @param readModels its read models (an automation's to-do list among them)
	 * @param producedDomainEvents the domain events its commands raise, or that it publishes for
	 * @param triggeringDomainEvents the domain events its commands are issued for: a policy's trigger
	 * @param inboundEvents its inbound integration events
	 * @param outboundEvents its outbound integration events
	 */
	public static SliceType derive ( int commands, int readModels, int producedDomainEvents, int triggeringDomainEvents, int inboundEvents, int outboundEvents ) {
		if ( commands == 0 && readModels == 0 && inboundEvents == 0 && outboundEvents == 0 && producedDomainEvents == 0 && triggeringDomainEvents == 0 ) {
			return UNDEFINED;
		}

		boolean stateChange = commands >= 1 && producedDomainEvents >= 1 && readModels == 0 && triggeringDomainEvents == 0 && inboundEvents == 0;
		boolean stateRead = commands == 0 && readModels >= 1 && triggeringDomainEvents == 0 && inboundEvents == 0 && outboundEvents == 0;
		boolean automation = commands >= 1 && readModels >= 1 && triggeringDomainEvents == 0 && inboundEvents == 0;
		// a policy: a command issued for a triggering domain event, with no to-do list in between
		boolean policy = commands >= 1 && triggeringDomainEvents >= 1 && readModels == 0 && inboundEvents == 0;
		boolean translation = commands >= 1 && readModels == 0 && triggeringDomainEvents == 0 && inboundEvents >= 1 && outboundEvents == 0;
		// a slice that only publishes: domain events mapped into an outbound event, reading read models or not,
		// with no command of its own -- the publishing slice several slices share for one integration event
		boolean publication = commands == 0 && producedDomainEvents >= 1 && triggeringDomainEvents == 0 && inboundEvents == 0 && outboundEvents >= 1;

		int matchCount = 0;
		SliceType matched = UNCLEAR;
		if ( stateChange ) { matchCount++; matched = STATE_CHANGE; }
		if ( stateRead ) { matchCount++; matched = STATE_READ; }
		if ( automation || policy || publication ) { matchCount++; matched = AUTOMATION; }
		if ( translation ) { matchCount++; matched = TRANSLATION; }

		return matchCount == 1 ? matched : UNCLEAR;
	}

}
