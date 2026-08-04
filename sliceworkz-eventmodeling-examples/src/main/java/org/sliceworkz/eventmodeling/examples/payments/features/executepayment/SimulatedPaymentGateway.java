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
package org.sliceworkz.eventmodeling.examples.payments.features.executepayment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for a real payment provider, rigged so the example can show every branch of
 * {@link ExecutePaymentAutomation#onFailure}. Not something to copy into an application — the automation
 * is the part to copy; this only exists to misbehave on cue.
 */
public class SimulatedPaymentGateway implements PaymentGateway {

	private final AtomicBoolean available = new AtomicBoolean(true);
	private final Map<String,AtomicInteger> declinesLeft = new ConcurrentHashMap<>();
	private final Map<String,String> executed = new ConcurrentHashMap<>();
	private final AtomicInteger calls = new AtomicInteger();

	/** Take the gateway down, or bring it back up. */
	public void available ( boolean available ) {
		this.available.set(available);
	}

	/** Decline the next {@code times} attempts for this IBAN, then accept. */
	public void declineNextAttempts ( String iban, int times ) {
		declinesLeft.put(iban, new AtomicInteger(times));
	}

	public int calls ( ) {
		return calls.get();
	}

	@Override
	public String execute ( String iban, long amountInCents, String idempotencyKey ) {
		calls.incrementAndGet();

		if ( !available.get() ) {
			throw new PaymentGatewayUnavailableException("gateway is down");
		}

		// A real gateway de-duplicates on the key it was given, which is why the automation passes one:
		// the money must not move twice even though the automation may attempt the payment twice.
		String alreadyDone = executed.get(idempotencyKey);
		if ( alreadyDone != null ) {
			return alreadyDone;
		}

		if ( !iban.startsWith("BE") ) {
			throw new PaymentRejectedException("unsupported IBAN " + iban);
		}

		AtomicInteger declines = declinesLeft.get(iban);
		if ( declines != null && declines.getAndDecrement() > 0 ) {
			throw new PaymentDeclinedException("daily limit reached for " + iban);
		}

		String reference = "GW-" + Integer.toHexString(idempotencyKey.hashCode());
		executed.put(idempotencyKey, reference);
		return reference;
	}

}
