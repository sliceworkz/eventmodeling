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

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent.PaymentInstructionReceived;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * External event → domain event: a payment instruction from outside becomes a {@code PaymentRequested},
 * which is what puts the payment on the automation's todo list.
 * <p>
 * The translator is where the outside world's vocabulary stops. What arrives is an <em>instruction</em>
 * someone else sent; what this context records is that a payment was <em>requested</em> of it. Here
 * the two carry the same fields, which is the common case and still worth a translator: the day the
 * partner's message changes shape, this class changes and the domain does not.
 *
 * <h2>Two idempotency keys, guarding two different things</h2>
 * <ul>
 *   <li>The application hands {@code incoming(...)} a key derived from the instruction, so an
 *       instruction delivered twice by the sender is stored once on the inbound stream.</li>
 *   <li>The translator keys the event it raises, derived from the same payment id, because the
 *       translator itself runs at least once: a crash between its append and its bookmark hands it
 *       the same instruction again, and without the key the payment would be requested — and so
 *       executed — twice.</li>
 * </ul>
 * Neither key is derived from the attempt or the time, or it would de-duplicate nothing.
 *
 * <h2>Where the rules go</h2>
 * An instruction is something that happened elsewhere, not a request this context may refuse, so the
 * translator does not reject it. An IBAN the gateway will not take is the automation's to find out,
 * and ends up as a {@code PaymentAbandoned} an operator can see — see {@code WHERE-VALIDATIONS-GO.md}
 * on validating at the edges.
 */
public class PaymentInstructionTranslator implements Translator<PaymentsInboundEvent,PaymentsDomainEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(PaymentInstructionReceived.class), Tags.none());
	}

	@Override
	public void translate ( PaymentsInboundEvent event, TranslatorContext<PaymentsInboundEvent,PaymentsDomainEvent> context ) {
		switch ( event ) {
			case PaymentInstructionReceived instruction -> context.event(
					new PaymentRequested(instruction.paymentId(), instruction.iban(), instruction.amountInCents()),
					PaymentsDomain.PAYMENT.tags(instruction.paymentId()),
					"payment-requested:" + instruction.paymentId().value());
		}
	}

}
