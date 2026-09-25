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
package org.sliceworkz.eventmodeling.examples.payments.features.receivepayment;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * External event → {@link PaymentInstructionTranslator} → {@code PaymentRequested}.
 * <p>
 * The same registered translator serves both ways in: {@code incoming(instruction, key)} stores the
 * instruction on the inbound stream and lets a processor translate it in the background, and
 * {@code translate(instruction)} runs it on the caller's thread without storing the instruction.
 * {@code PaymentsExample} uses the first; {@code PaymentInstructionTranslatorTest} the second.
 */
@FeatureSlice(type = Type.TRANSLATION, context = "payments", chapter = "Receiving Payments",
	tags = {"inbound", "translation"})
public class ReceivePaymentFeatureSlice implements Slice<Payments> {

	@Override
	public void configureAutomation ( BoundedContextBuilder<Payments> builder ) {
		builder.translator(new PaymentInstructionTranslator());
	}

}
