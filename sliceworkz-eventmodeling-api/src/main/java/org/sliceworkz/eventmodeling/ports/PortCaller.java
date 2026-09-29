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
package org.sliceworkz.eventmodeling.ports;

/**
 * Who called a port: the component of the bounded context whose code was running on the calling thread.
 * The framework knows it at every point it hands control to user code — a command's {@code execute}, an
 * automation's {@code handle}/{@code onFailure}, a translator, a dispatcher, a read model's {@code when},
 * a live model read. A call from anywhere else through a port taken from the context a feature slice was
 * handed in its {@code start...} methods — the REST endpoint it wired there, on a request thread, whether it
 * took the port then or looks it up per request — is that {@link #slice slice}'s, and one through a port
 * taken from the built context by application code is {@link #UNATTRIBUTED}.
 *
 * @param kind one of the {@code KIND_*} constants. A string rather than an enum, so a reader built before a
 *        new kind existed still reads the event
 * @param name the component's name as its own events name it: a command's name, an automation's class name,
 *        a read model's {@code readmodelName()}, a slice's name
 */
public record PortCaller ( String kind, String name ) {

	public static final String KIND_COMMAND = "COMMAND";
	public static final String KIND_AUTOMATION = "AUTOMATION";
	public static final String KIND_TRANSLATOR = "TRANSLATOR";
	public static final String KIND_DISPATCHER = "DISPATCHER";
	public static final String KIND_READ_MODEL = "READ_MODEL";
	public static final String KIND_SLICE = "SLICE";
	public static final String KIND_UNATTRIBUTED = "UNATTRIBUTED";

	/** A call made outside any component the framework invoked. */
	public static final PortCaller UNATTRIBUTED = new PortCaller(KIND_UNATTRIBUTED, null);

	public static PortCaller command ( String name ) {
		return new PortCaller(KIND_COMMAND, name);
	}

	public static PortCaller automation ( String name ) {
		return new PortCaller(KIND_AUTOMATION, name);
	}

	public static PortCaller translator ( String name ) {
		return new PortCaller(KIND_TRANSLATOR, name);
	}

	public static PortCaller dispatcher ( String name ) {
		return new PortCaller(KIND_DISPATCHER, name);
	}

	public static PortCaller readModel ( String name ) {
		return new PortCaller(KIND_READ_MODEL, name);
	}

	/**
	 * A feature slice's own code outside any component: a REST endpoint it wired in its {@code start...}
	 * method, calling a port of the context it was handed there, on a request thread the framework did not
	 * start.
	 */
	public static PortCaller slice ( String name ) {
		return new PortCaller(KIND_SLICE, name);
	}

}
