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

public class ExceptionWhileHandlingEventWithReference extends RuntimeException {
	
	private EventReference eventReference;
	
	public ExceptionWhileHandlingEventWithReference ( Throwable t, EventReference eventReference ) {
		super(t);
		this.eventReference = eventReference;
	}
	
	public EventReference eventReference ( ) {
		return eventReference;
	}
	
	public static void throwFor ( Throwable t, EventReference eventReference ) {
		throw new ExceptionWhileHandlingEventWithReference(t, eventReference);
	}
	
}