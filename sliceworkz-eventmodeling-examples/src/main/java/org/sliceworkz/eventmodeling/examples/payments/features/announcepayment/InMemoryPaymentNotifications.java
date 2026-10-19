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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;

/**
 * Stands in for the message broker: it keeps what was published, and de-duplicates on the message key
 * the way a receiving system should. It counts every call too, so a caller can see a redelivery reach
 * it and still leave one message behind.
 */
public class InMemoryPaymentNotifications implements PaymentNotifications {

	private final Map<String,PaymentAnnounced> published = new LinkedHashMap<>();
	private int calls;

	@Override
	public synchronized void publish ( String messageKey, PaymentAnnounced announcement ) {
		calls++;
		published.putIfAbsent(messageKey, announcement);
	}

	/** Every distinct announcement received, in the order it first arrived. */
	public synchronized List<PaymentAnnounced> published ( ) {
		return new ArrayList<>(published.values());
	}

	/** How many times {@link #publish} was called, repeats included. */
	public synchronized int calls ( ) {
		return calls;
	}

}
