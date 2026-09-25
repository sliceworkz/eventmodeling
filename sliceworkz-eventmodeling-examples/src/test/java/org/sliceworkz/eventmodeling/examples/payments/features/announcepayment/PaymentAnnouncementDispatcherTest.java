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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.AnnouncePaymentCommand;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.testing.DispatcherTest;

/**
 * The worked example of testing a dispatcher with {@link DispatcherTest}: seed the outbound stream,
 * dispatch, and assert on the fake external system. The dispatcher is driven by the harness and never
 * registered, so nothing races the test thread.
 */
public class PaymentAnnouncementDispatcherTest extends DispatcherTest<PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

	private static final PaymentId PAYMENT_1 = PaymentsDomain.PAYMENT.id("p1");
	private static final PaymentId PAYMENT_2 = PaymentsDomain.PAYMENT.id("p2");

	private final InMemoryPaymentNotifications notifications = new InMemoryPaymentNotifications();

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
	public Dispatcher<PaymentsOutboundEvent> dispatcher ( ) {
		return new PaymentAnnouncementDispatcher(notifications);
	}

	@Test
	void anAnnouncementPublishedByTheCommandReachesTheReceiver ( ) {
		// seeded through the real outbound command, under the key publishAndRecord would give it
		given()
			.givenExecuted(new AnnouncePaymentCommand(PAYMENT_1, "GW-1"), "payment/p1/outbound")
			.whenDispatched()
			.delivered(1);

		assertEquals(List.of(new PaymentAnnounced(PAYMENT_1, "GW-1")), notifications.published());
	}

	@Test
	void aSecondRoundDeliversOnlyWhatIsNew ( ) {
		given(new PaymentAnnounced(PAYMENT_1, "GW-1"))
			.whenDispatched()
			.delivered(1)
			.and()
			.given(new PaymentAnnounced(PAYMENT_2, "GW-2"))
			.whenDispatched()
			.delivered(1);

		assertEquals(2, notifications.calls());
	}

	@Test
	void aLostBookmarkRepublishesUnderTheSameKeysSoTheReceiverKeepsOneOfEach ( ) {
		given(new PaymentAnnounced(PAYMENT_1, "GW-1"), new PaymentAnnounced(PAYMENT_2, "GW-2"))
			.whenDispatched()
			.delivered(2)
			.and()
			// the bookmark is gone -- a crash before it was placed, or the dispatcher class renamed
			.whenRedeliveredFromTheStart()
			.delivered(2);

		assertEquals(4, notifications.calls(), "everything was published a second time");
		assertEquals(2, notifications.published().size(), "and the receiver recognised every repeat by its key");
	}

}
