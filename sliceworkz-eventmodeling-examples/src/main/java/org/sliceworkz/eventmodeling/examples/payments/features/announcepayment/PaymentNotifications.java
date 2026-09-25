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

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent.PaymentAnnounced;

/**
 * The port through which payment announcements leave the context — a message broker, a webhook, a
 * partner's API. The slice asks for it with {@code builder.port(PaymentNotifications.class)}; which
 * implementation answers is the application's choice, bound with
 * {@code .adapter(notifications).forPort(PaymentNotifications.class)}.
 */
public interface PaymentNotifications {

	/**
	 * Publishes one announcement.
	 *
	 * @param messageKey stable for the announcement, whichever attempt publishes it — hand it to the
	 *        receiving system as its de-duplication key, since a dispatcher delivers at least once
	 * @param announcement what to publish
	 */
	void publish ( String messageKey, PaymentAnnounced announcement );

}
