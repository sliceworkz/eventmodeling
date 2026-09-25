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

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Delivers {@code PaymentAnnounced} from the outbound stream to {@link PaymentNotifications} — the
 * outbox pattern: the announcement was stored in the same event store as the fact it announces, and
 * this is what carries it out.
 *
 * <h2>At least once, so the receiver de-duplicates</h2>
 * A dispatcher is a projection over the outbound stream with a bookmark, projected on one elected
 * instance. A crash between publishing and bookmarking, or a failover, hands it the same event again,
 * and nothing on this side can make the external call and the bookmark one step. So each announcement
 * goes out under a key derived from the payment, identical on every delivery, for the receiving system
 * to recognise a repeat by.
 *
 * <h2>Its name is its bookmark</h2>
 * The class' simple name keys the bookmark that records how far publishing has got. Renaming the class
 * starts a new bookmark, and a new bookmark means publishing the whole outbound stream again — so treat
 * the name as the stored data it is. It also has to be a named class for the same reason: an anonymous
 * or generated one would get a fresh name, and a fresh bookmark, on every start.
 *
 * <h2>When the receiver is down</h2>
 * A throw from {@code publish} fails the batch; the projector rolls its cursor back and the processor
 * retries with backoff, so nothing is skipped. An operator can also hold the dispatcher back for the
 * length of an outage with {@code stopProcessor(ProcessorKind.DISPATCHER, "PaymentAnnouncementDispatcher")}
 * and start it again afterwards.
 */
public class PaymentAnnouncementDispatcher implements Dispatcher<PaymentsOutboundEvent> {

	private final PaymentNotifications notifications;

	public PaymentAnnouncementDispatcher ( PaymentNotifications notifications ) {
		this.notifications = notifications;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(PaymentAnnounced.class), Tags.none());
	}

	@Override
	public void when ( Event<PaymentsOutboundEvent> event ) {
		switch ( event.data() ) {
			case PaymentAnnounced announced -> notifications.publish("payment-announced/" + announced.paymentId().value(), announced);
		}
	}

}
