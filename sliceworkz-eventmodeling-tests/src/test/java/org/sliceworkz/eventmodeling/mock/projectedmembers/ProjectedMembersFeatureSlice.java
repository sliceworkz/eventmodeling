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
package org.sliceworkz.eventmodeling.mock.projectedmembers;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.mock.publishedstatechange.PublishedStateChangeCommand;
import org.sliceworkz.eventmodeling.mock.publishing.ItemReadModel;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * A command with a live lookup only it reads, registered in the command aspect, beside a read model kept in
 * the background: each announced with the projection its registration says, not one guessed from the aspect.
 */
@FeatureSlice(chapter = "Projection")
public class ProjectedMembersFeatureSlice implements Slice<Mock> {

	@Override
	public void configureCommand ( BoundedContextBuilder<Mock> builder ) {
		builder.command(PublishedStateChangeCommand.class);
		builder.readmodel(ItemReadModel.class).live();
	}

	@Override
	public void configureAutomation ( BoundedContextBuilder<Mock> builder ) {
		builder.readmodel(new MockReadModel("background-model")).eventuallyConsistent();
	}

}
