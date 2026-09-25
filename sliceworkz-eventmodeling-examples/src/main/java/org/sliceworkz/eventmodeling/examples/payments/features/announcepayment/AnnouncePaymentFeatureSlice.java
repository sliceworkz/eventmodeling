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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * {@code PaymentAnnounced} → {@link PaymentAnnouncementDispatcher} → {@link PaymentNotifications}.
 * <p>
 * The outbound half of the story {@code ExecutePaymentFeatureSlice} starts: the automation publishes
 * the announcement onto the outbound stream, and this slice carries it to whoever listens. The
 * destination is a port, bound by the application, for the same reason the payment gateway is one.
 */
@FeatureSlice(type = Type.UNDEFINED, context = "payments", chapter = "Executing Payments",
	tags = {"outbound", "dispatcher"})
public class AnnouncePaymentFeatureSlice implements Slice<Payments> {

	@Override
	public void configureAutomation ( BoundedContextBuilder<Payments> builder ) {
		builder.dispatcher(new PaymentAnnouncementDispatcher(builder.port(PaymentNotifications.class)));
	}

}
