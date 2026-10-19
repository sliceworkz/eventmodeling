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

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Tells the world a payment went through: maps {@code PaymentExecuted} — the fact the automation
 * recorded — into {@code PaymentAnnounced}, the published language other systems consume.
 * <p>
 * The mapping is the whole of it. A publisher decides nothing: what was decided, the gateway accepting
 * the payment, is already a domain event by the time it gets here, which is why the automation records
 * {@code PaymentExecuted} and publishes nothing itself. Nor does it call out; that is
 * {@link PaymentAnnouncementDispatcher}'s job, one hop further.
 * <p>
 * Nothing here is keyed by hand. The framework keys each announcement by the id of the domain event it
 * was published for and tags it {@code x-published-from} with that id, so a redelivered
 * {@code PaymentExecuted} — a crash before the bookmark, a failover — publishes nothing a second time,
 * and every announcement traces back to the execution it announces.
 * <p>
 * It reads no read model: everything the announcement carries is on the event. A publisher that needs
 * more declares the live read models it reads in {@code reads()} and reads them as of the event, so a
 * consumer is told the state the decision produced rather than whatever it is by the time this runs.
 */
public class PaymentAnnouncedPublisher implements Publisher<PaymentsDomainEvent,PaymentsOutboundEvent> {

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forTypes(PaymentExecuted.class);
	}

	@Override
	public void publish ( Event<PaymentsDomainEvent> event, PublisherContext<PaymentsDomainEvent,PaymentsOutboundEvent> context ) {
		if ( event.data() instanceof PaymentExecuted executed ) {
			context.publish(new PaymentAnnounced(executed.paymentId(), executed.gatewayReference()),
					PaymentsDomain.PAYMENT.tags(executed.paymentId()));
		}
	}

}
