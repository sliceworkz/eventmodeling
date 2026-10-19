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
package org.sliceworkz.eventmodeling.examples.payments.features.receivepayment;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent.PaymentInstructionReceived;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.testing.TranslatorTest;

/**
 * The worked example of testing a translator with {@link TranslatorTest}: an inbound event in, the
 * domain events it raised out, synchronously on the test thread. The harness also checks, on every
 * test, that the interactive path left the inbound event unstored.
 */
public class PaymentInstructionTranslatorTest extends TranslatorTest<PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

	private static final PaymentId PAYMENT_1 = PaymentsDomain.PAYMENT.id("p1");

	@Override
	public Class<PaymentsDomainEvent> domainEventType ( ) {
		return PaymentsDomainEvent.class;
	}

	@Override
	public Class<PaymentsInboundEvent> inboundEventType ( ) {
		return PaymentsInboundEvent.class;
	}

	@Override
	public Class<PaymentsOutboundEvent> outboundEventType ( ) {
		return PaymentsOutboundEvent.class;
	}

	@Override
	public List<Translator<PaymentsInboundEvent, PaymentsDomainEvent>> translators ( ) {
		return List.of(new PaymentInstructionTranslator());
	}

	@Test
	void anInstructionBecomesARequestedPaymentTaggedWithThePayment ( ) {
		given()
			.when(new PaymentInstructionReceived(PAYMENT_1, "BE68539007547034", 100_00))
			.then()
			// tagged with the payment, so a query for one payment finds its whole history
			.event(new PaymentRequested(PAYMENT_1, "BE68539007547034", 100_00), PaymentsDomain.PAYMENT.tags(PAYMENT_1));
	}

	@Test
	void anInstructionTranslatedAgainRequestsThePaymentOnce ( ) {
		// what a translator re-run after a crash between its append and its bookmark amounts to: the
		// same instruction translated a second time, which the payment-derived key swallows
		given()
			.when(new PaymentInstructionReceived(PAYMENT_1, "BE68539007547034", 100_00))
			.when(new PaymentInstructionReceived(PAYMENT_1, "BE68539007547034", 100_00))
			.then()
			.noEvents();
	}

}
