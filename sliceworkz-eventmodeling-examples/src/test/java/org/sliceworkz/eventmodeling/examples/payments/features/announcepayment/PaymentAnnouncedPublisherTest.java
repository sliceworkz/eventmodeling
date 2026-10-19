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
package org.sliceworkz.eventmodeling.examples.payments.features.announcepayment;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.testing.PublisherTest;

/**
 * The worked example of testing a publisher with {@link PublisherTest}: seed domain events, publish, and
 * assert on what reached the outbound stream — through the same publication a deployed publisher runs,
 * so the keys and the {@code x-published-from} tag are the production ones.
 */
public class PaymentAnnouncedPublisherTest extends PublisherTest<PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

	private static final PaymentId PAYMENT_1 = PaymentsDomain.PAYMENT.id("p1");
	private static final PaymentId PAYMENT_2 = PaymentsDomain.PAYMENT.id("p2");

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
	public Publisher<PaymentsDomainEvent, PaymentsOutboundEvent> publisher ( ) {
		return new PaymentAnnouncedPublisher();
	}

	@Test
	void anExecutedPaymentIsAnnouncedTaggedWithThePayment ( ) {
		given(new PaymentRequested(PAYMENT_1, "BE68539007547034", 100_00),
				new PaymentExecuted(PAYMENT_1, "GW-1"))
			.whenPublished()
			.published(new PaymentAnnounced(PAYMENT_1, "GW-1"), PaymentsDomain.PAYMENT.tags(PAYMENT_1));
	}

	@Test
	void anAbandonedPaymentIsNotAnnounced ( ) {
		given(new PaymentRequested(PAYMENT_2, "XX99999999999999", 50_00),
				new PaymentAbandoned(PAYMENT_2, "unsupported IBAN XX99999999999999", 1))
			.whenPublished()
			.nothingPublished();
	}

	@Test
	void aRedeliveredExecutionIsNotAnnouncedTwice ( ) {
		given(new PaymentExecuted(PAYMENT_1, "GW-1"))
			.whenPublished()
			.published(new PaymentAnnounced(PAYMENT_1, "GW-1"))
			.and()
			// a crash between the append and the bookmark hands the event over again: the announcement
			// is keyed by the domain event's id, so the second round appends nothing
			.whenRepublished()
			.nothingPublished();
	}

}
