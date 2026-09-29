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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The adapter behind {@link GatewayPort}: does what each method's name says, and counts the calls that
 * reached it, so a test can tell a call that went through the proxy from one the proxy swallowed.
 */
public class ScriptedGateway implements GatewayPort {

	/** Thrown by {@link #crash()}. */
	public static final class GatewayError extends Error {
		private static final long serialVersionUID = 1L;
		public GatewayError ( String message ) {
			super(message);
		}
	}

	public final AtomicInteger calls = new AtomicInteger();

	@Override
	public String answer ( String question ) {
		calls.incrementAndGet();
		return "answer to " + question;
	}

	@Override
	public String nothing ( ) {
		calls.incrementAndGet();
		return null;
	}

	@Override
	public String refuse ( String reason ) {
		calls.incrementAndGet();
		throw new NotFoundException(reason);
	}

	@Override
	public String decline ( String reason ) {
		calls.incrementAndGet();
		throw new DeclinedException(reason);
	}

	@Override
	public String declineHard ( String reason ) {
		calls.incrementAndGet();
		throw new HardDeclinedException(reason);
	}

	@Override
	public String breakDown ( String reason ) {
		calls.incrementAndGet();
		throw new IllegalStateException(reason);
	}

	@Override
	public String unreachable ( ) {
		calls.incrementAndGet();
		throw new GatewayDownException("gateway down");
	}

	@Override
	public String readFile ( ) throws IOException {
		calls.incrementAndGet();
		throw new IOException("disk gone");
	}

	@Override
	public String crash ( ) {
		calls.incrementAndGet();
		throw new GatewayError("out of everything");
	}

}
