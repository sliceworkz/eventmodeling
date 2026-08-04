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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/** The dead-letter view over the payments the automation gave up on. */
@FeatureSlice(type = Type.STATE_READ, context = "payments", chapter = "Executing Payments",
	tags = {"dead-letter", "operations"})
public class AbandonedPaymentsFeatureSlice implements Slice<Payments> {

	@Override
	public void configureQuery ( BoundedContextBuilder<Payments> builder ) {
		builder.readmodel(AbandonedPaymentsReadModel.class);
	}

}
