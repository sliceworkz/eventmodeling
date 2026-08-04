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

/**
 * The outside world, as far as this automation is concerned.
 * <p>
 * The three failure shapes are what the automation's {@code onFailure} is written against, and they are
 * worth distinguishing in your own adapter too, because they call for opposite responses. The
 * distinction that matters is not "transient vs permanent" but <strong>whose problem is it</strong>:
 * a gateway that is down fails every payment in the batch, while a rejected payment is a fact about
 * that one payment and says nothing about the next.
 */
public interface PaymentGateway {

	/**
	 * @return the gateway's own reference for the payment
	 * @throws PaymentGatewayUnavailableException the gateway is not reachable — nothing to do with this payment
	 * @throws PaymentDeclinedException this payment was declined, but may be accepted later
	 * @throws PaymentRejectedException this payment will never be accepted
	 */
	String execute ( String iban, long amountInCents, String idempotencyKey );

	/** The gateway is down. Every payment would fail right now, so there is no point trying the next one. */
	class PaymentGatewayUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public PaymentGatewayUnavailableException ( String message ) {
			super(message);
		}
	}

	/** This payment was declined for a reason that may not hold later (a limit, a temporary block). */
	class PaymentDeclinedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public PaymentDeclinedException ( String message ) {
			super(message);
		}
	}

	/** This payment is wrong and will stay wrong — a malformed IBAN, a closed account. */
	class PaymentRejectedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public PaymentRejectedException ( String message ) {
			super(message);
		}
	}

}
