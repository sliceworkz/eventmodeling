/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import org.sliceworkz.eventstore.events.EventReference;

public record ThrowableAndEventReference ( Throwable throwable, EventReference eventReference ) { 
	
	ThrowableAndEventReference cause ( ) {
		return new ThrowableAndEventReference(throwable.getCause(), eventReference);
	}
	
	ThrowableAndEventReference withEventReference ( EventReference eventReference ) {
		return new ThrowableAndEventReference(throwable, eventReference);
	}

	
	
	public static ThrowableAndEventReference determineRootCause ( Throwable t ) {
		return determineRootCause(new ThrowableAndEventReference(t, null));
	}
	
	private static ThrowableAndEventReference determineRootCause ( ThrowableAndEventReference t ) {
		if ( ExceptionWhileHandlingEventWithReference.class.isAssignableFrom(t.throwable().getClass() ) ) {
			t = t.withEventReference ( ((ExceptionWhileHandlingEventWithReference)t.throwable()).eventReference() );
		}
		if ( t.throwable().getCause() == null ) {
			return t;
		} else {
			return determineRootCause ( t.cause() );
		}
	}
	
}
