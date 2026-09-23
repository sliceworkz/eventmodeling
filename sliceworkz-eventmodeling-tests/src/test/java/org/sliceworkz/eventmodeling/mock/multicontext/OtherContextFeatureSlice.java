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
package org.sliceworkz.eventmodeling.mock.multicontext;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * A slice of the other context, sharing a root package with {@link MockContextFeatureSlice}. Building
 * the Mock context over this package must neither run this slice's configuration — it would register a
 * read model of event types the Mock context does not hold — nor count it among the Mock context's
 * slices.
 */
@FeatureSlice(type = Type.STATE_READ, context = "other")
public class OtherContextFeatureSlice implements Slice<OtherContext> {

	@Override
	public void configureQuery ( BoundedContextBuilder<OtherContext> builder ) {
		builder.readmodel(OtherContextReadModel.class).live();
	}

}
