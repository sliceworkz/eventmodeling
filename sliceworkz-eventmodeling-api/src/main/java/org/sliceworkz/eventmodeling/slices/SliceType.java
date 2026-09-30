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
 * code and the same slice in the model come out as the same type: {@link #derive(int, int, int, int, int)}
 * is that rule over the counts of the slice's elements — the modeler calls it for its modeled slices — and {@link #of(Collection)} maps the registered
 * components onto those elements:
 * <ul>
 * <li>a command and an aggregate are a command raising domain events</li>
 * <li>a read model is a read model</li>
 * <li>an automation is a command raised from a read model — the to-do list it works, which it counts
 *     as whether or not the same slice registers it</li>
 * <li>a translator is an inbound event and the command it is turned into</li>
 * <li>a dispatcher is an outbound event</li>
 * </ul>
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
	 * A processor working a to-do list by issuing commands: events → to-do list → processor → command → event.
	 * Also a publication — domain events mapped into an outbound event, with no command of its own — which
	 * is the part of an automation slice that tells the world what was recorded.
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
		int commands = 0, readModels = 0, inbound = 0, outbound = 0;
		if ( members != null ) {
			for ( SliceMember member : members ) {
				MemberKind kind = member.kind();
				if ( kind == null ) {
					continue;
				}
				switch ( kind ) {
					case COMMAND, AGGREGATE -> commands++;
					case READ_MODEL -> readModels++;
					case AUTOMATION -> { commands++; readModels++; }
					case TRANSLATOR -> { commands++; inbound++; }
					// a publisher is what the model shows as an integration event linked to the slice whose
					// domain event it publishes: an outbound event, whatever else the slice is
					case PUBLISHER, DISPATCHER -> outbound++;
				}
			}
		}
		return derive(commands, readModels, commands, inbound, outbound);
	}

	/**
	 * The Sliceworkz Modeler's rule, over the counts of a slice's elements: exactly one pattern has to
	 * match for the slice to be of that type, none of the counts is {@link #UNDEFINED}, anything else is
	 * {@link #UNCLEAR}.
	 */
	public static SliceType derive ( int commands, int readModels, int producedDomainEvents, int inboundEvents, int outboundEvents ) {
		if ( commands == 0 && readModels == 0 && inboundEvents == 0 && outboundEvents == 0 && producedDomainEvents == 0 ) {
			return UNDEFINED;
		}

		boolean stateChange = commands >= 1 && producedDomainEvents >= 1 && readModels == 0 && inboundEvents == 0;
		boolean stateRead = commands == 0 && readModels >= 1 && inboundEvents == 0 && outboundEvents == 0;
		boolean automation = commands >= 1 && readModels >= 1 && inboundEvents == 0;
		boolean translation = commands >= 1 && readModels == 0 && inboundEvents >= 1 && outboundEvents == 0;
		// a publication: domain events mapped into an outbound event, reading a read model or not, with no
		// command of its own -- the part of an automation slice that tells the world what was recorded
		boolean publication = commands == 0 && producedDomainEvents >= 1 && inboundEvents == 0 && outboundEvents >= 1;

		int matchCount = 0;
		SliceType matched = UNCLEAR;
		if ( stateChange ) { matchCount++; matched = STATE_CHANGE; }
		if ( stateRead ) { matchCount++; matched = STATE_READ; }
		if ( automation || publication ) { matchCount++; matched = AUTOMATION; }
		if ( translation ) { matchCount++; matched = TRANSLATION; }

		return matchCount == 1 ? matched : UNCLEAR;
	}

}
