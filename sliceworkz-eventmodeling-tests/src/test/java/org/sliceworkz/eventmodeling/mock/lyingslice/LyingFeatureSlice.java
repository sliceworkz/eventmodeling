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
package org.sliceworkz.eventmodeling.mock.lyingslice;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.multicontext.OtherContextReadModel;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * A slice of the Mock context that registers another context's read model — the mistake the
 * context-type filter on the scan cannot catch, since this slice really is a {@code Slice<Mock>}.
 * What catches it is the registration check, which is why that has to run after the scan.
 */
@FeatureSlice(type = Type.STATE_READ, context = "mock")
public class LyingFeatureSlice implements Slice<Mock> {

	@Override
	public void configureQuery ( BoundedContextBuilder<Mock> builder ) {
		builder.readmodel(OtherContextReadModel.class).live();
	}

}
