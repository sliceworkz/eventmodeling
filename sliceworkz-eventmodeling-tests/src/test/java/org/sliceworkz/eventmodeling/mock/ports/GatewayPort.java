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
package org.sliceworkz.eventmodeling.mock.ports;

import java.io.IOException;

/**
 * A port with one method per way a call can end, for the monitored-port tests.
 */
public interface GatewayPort {

	/** Returns normally. */
	String answer ( String question );

	/** Returns null, which a proxy must hand back as null. */
	String nothing ( );

	/** Throws a {@code BusinessException} subtype: a business answer by default. */
	String refuse ( String reason );

	/** Throws {@link DeclinedException}, a third-party type only a declaration makes a business answer. */
	String decline ( String reason );

	/** Throws {@link HardDeclinedException}, a subtype of the declared one. */
	String declineHard ( String reason );

	/** Throws an {@code IllegalStateException}: a failure. */
	String breakDown ( String reason );

	/** Throws a {@code PortUnavailableException}: always a failure. */
	String unreachable ( );

	/** Throws a checked exception it declares, which must reach the caller as itself. */
	String readFile ( ) throws IOException;

	/** Throws an {@code Error}. */
	String crash ( );

	/** A default method: answered by the adapter's own implementation of the interface. */
	default String greet ( String name ) {
		return "hello " + name;
	}

	/** A third-party "no": not a {@code BusinessException}. */
	class DeclinedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public DeclinedException ( String message ) {
			super(message);
		}
	}

	/** A subtype of a declared business exception is a business exception too. */
	class HardDeclinedException extends DeclinedException {
		private static final long serialVersionUID = 1L;
		public HardDeclinedException ( String message ) {
			super(message);
		}
	}

	/** The port's "not found": a {@code BusinessException}. */
	class NotFoundException extends org.sliceworkz.eventmodeling.commands.BusinessException {
		private static final long serialVersionUID = 1L;
		public NotFoundException ( String message ) {
			super(message);
		}
	}

	/** The port's own "cannot reach". */
	class GatewayDownException extends org.sliceworkz.eventmodeling.ports.PortUnavailableException {
		private static final long serialVersionUID = 1L;
		public GatewayDownException ( String message ) {
			super(message);
		}
	}

}
