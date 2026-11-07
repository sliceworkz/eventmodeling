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
package org.sliceworkz.eventmodeling.examples.banking.features.inboundtraffic;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.Banking;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * What the bank received from outside: a read model over the inbound stream, registered on
 * {@code builder.inbound()} and read through {@code banking.inbound().read(...)}.
 */
@FeatureSlice(chapter="Integration")
public class InboundTrafficFeatureSlice implements Slice<Banking> {

	@Override
	public void configureQuery ( BoundedContextBuilder<Banking> builder ) {
		builder.inbound().readmodel(InboundTrafficReadModel.INSTANCE).eventuallyConsistent();
	}

}
