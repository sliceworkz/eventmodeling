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
package org.sliceworkz.eventmodeling.examples.payments.features.abandonedpayments;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * The dead-letter view: every payment the automation gave up on, and why.
 * <p>
 * Worth noticing how little there is to it. Because giving up was recorded as a domain event, the
 * "dead-letter queue" is an ordinary read model over ordinary events — queryable, replayable, and
 * surviving restarts, none of which a list held inside the framework would have been.
 */
public class AbandonedPaymentsReadModel implements ReadModel<PaymentsDomainEvent> {

	public record AbandonedPayment ( String paymentId, String reason, int attempts ) { }

	private final List<AbandonedPayment> abandoned = new ArrayList<>();

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(EventTypesFilter.of(PaymentAbandoned.class), Tags.none());
	}

	@Override
	public void when ( PaymentsDomainEvent event ) {
		if ( event instanceof PaymentAbandoned a ) {
			abandoned.add(new AbandonedPayment(a.paymentId().value(), a.reason(), a.attempts()));
		}
	}

	public List<AbandonedPayment> abandoned ( ) {
		return List.copyOf(abandoned);
	}

}
