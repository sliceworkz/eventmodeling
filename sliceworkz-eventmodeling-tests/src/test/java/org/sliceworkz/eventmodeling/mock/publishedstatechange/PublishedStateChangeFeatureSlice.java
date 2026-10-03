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
package org.sliceworkz.eventmodeling.mock.publishedstatechange;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.publishing.ItemPublisher;
import org.sliceworkz.eventmodeling.mock.publishing.ItemReadModel;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * A state change whose integration event is published from the slice itself, with a lookup the publisher
 * reads to build its message: the lookup is part of the publication, so the slice stays a state change.
 */
@FeatureSlice(chapter = "Publishing")
public class PublishedStateChangeFeatureSlice implements Slice<Mock> {

	@Override
	public void configureCommand ( BoundedContextBuilder<Mock> builder ) {
		builder.command(PublishedStateChangeCommand.class);
	}

	@Override
	public void configureAutomation ( BoundedContextBuilder<Mock> builder ) {
		builder
			.readmodel(ItemReadModel.class).live()
			.publisher(new ItemPublisher());
	}

}
