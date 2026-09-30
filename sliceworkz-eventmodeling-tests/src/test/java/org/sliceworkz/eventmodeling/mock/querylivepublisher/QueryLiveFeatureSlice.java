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
package org.sliceworkz.eventmodeling.mock.querylivepublisher;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.publishing.ItemPublisher;
import org.sliceworkz.eventmodeling.mock.publishing.ItemReadModel;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Registers the read model its publisher reads from {@code configureQuery} only: fine on an instance that
 * deploys queries too, refused on one that runs automations alone.
 */
@FeatureSlice(chapter = "Publishing")
public class QueryLiveFeatureSlice implements Slice<Mock> {

	@Override
	public void configureQuery ( BoundedContextBuilder<Mock> builder ) {
		builder.readmodel(ItemReadModel.class).live();
	}

	@Override
	public void configureAutomation ( BoundedContextBuilder<Mock> builder ) {
		builder.publisher(new ItemPublisher());
	}

}
