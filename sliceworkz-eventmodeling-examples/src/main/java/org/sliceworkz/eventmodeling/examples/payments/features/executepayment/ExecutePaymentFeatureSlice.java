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

import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Events → {@link PaymentsToExecuteTodoList} → {@link ExecutePaymentAutomation} → gateway → Events.
 * <p>
 * The automation itself is registered by the application rather than here, because it needs a
 * {@link PaymentGateway} — an adapter onto something outside the context, which a slice discovered by
 * package scanning has no way to construct. See {@code PaymentsExample} for the two lines that do it.
 * This slice carries the metadata, so the feature still shows up in the model an observer builds from
 * {@code BoundedContextStarting}.
 */
@FeatureSlice(type = Type.AUTOMATION, context = "payments", chapter = "Executing Payments",
	tags = {"automation", "outbound", "failure-handling"})
public class ExecutePaymentFeatureSlice implements Slice<Payments> {
}
