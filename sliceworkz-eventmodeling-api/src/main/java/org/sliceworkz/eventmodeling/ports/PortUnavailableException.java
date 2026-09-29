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
 * Thrown by a port — or by an adapter, for its own "cannot reach" exception to extend — when the port could
 * not be called: the system behind it is down, unreachable, or refusing work. Always reported as a
 * {@code PortCallFailed}, whatever {@link PortMonitoring#businessExceptions} declares, since it is the one
 * kind of failure that is never an answer.
 * <p>
 * Extending it is what lets one type serve every consumer of the failure: an automation's
 * {@code onFailure} matches it to retry rather than stop, and an HTTP binding maps it to
 * {@code 503 Service Unavailable} — the call can be retried later, and nothing was appended.
 */
public class PortUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PortUnavailableException ( String message ) {
		super(message);
	}

	public PortUnavailableException ( String message, Throwable cause ) {
		super(message, cause);
	}

}
