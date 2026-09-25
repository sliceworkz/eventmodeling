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

import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;

/**
 * Publishes {@link PaymentAnnounced} on the outbound stream — the only way anything reaches that
 * stream, since a dispatcher only reads it.
 * <p>
 * An {@code OutboundCommand} appends to the outbound stream and nowhere else, which is why it cannot
 * also record {@code PaymentExecuted}: "record the fact and publish it" is two appends on two streams,
 * and it is {@code ExecutePaymentAutomation} that composes them, through
 * {@code AutomationContext.publishAndRecord}.
 * <p>
 * It is offered no decision models, and needs none: what it publishes was decided by the gateway
 * accepting the payment, and a boundary read from the domain stream could not guard an append to the
 * outbound one anyway.
 * <p>
 * {@code requireIdempotencyKey()} makes the key {@code publishAndRecord} hands it mandatory rather than
 * incidental: executed any other way without a key, it fails before anything is stored, instead of
 * publishing a payment twice the first time an automation is re-handed its item.
 */
public class AnnouncePaymentCommand implements OutboundCommand<PaymentsDomainEvent,PaymentsOutboundEvent> {

	private final PaymentId paymentId;
	private final String gatewayReference;

	public AnnouncePaymentCommand ( PaymentId paymentId, String gatewayReference ) {
		this.paymentId = paymentId;
		this.gatewayReference = gatewayReference;
	}

	@Override
	public void execute ( OutboundCommandContext<PaymentsDomainEvent,PaymentsOutboundEvent> context ) {
		context.noDecisionModels()
			.requireIdempotencyKey()
			.raiseEvent(new PaymentAnnounced(paymentId, gatewayReference), PaymentsDomain.PAYMENT.tags(paymentId));
	}

}
