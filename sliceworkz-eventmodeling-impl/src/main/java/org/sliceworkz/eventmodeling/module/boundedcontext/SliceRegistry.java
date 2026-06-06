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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Resolves the originating feature slice of a component (command, read model, aggregate, automation)
 * by package convention: a component is attributed to the {@code @FeatureSlice} discovered in the
 * same package.
 * <p>
 * Built from the deployed feature slices of a bounded context. Components located outside any known
 * slice package resolve to {@code null}.
 */
public final class SliceRegistry {

	private final Map<String, BoundedContextEvent.FeatureSlice> byPackage = new HashMap<>();

	public SliceRegistry ( Collection<? extends Slice<?>> slices ) {
		for ( Slice<?> slice : slices ) {
			byPackage.putIfAbsent(slice.getClass().getPackageName(), toFeatureSlice(slice));
		}
	}

	/**
	 * @param componentClass the class of the component to attribute (may be {@code null})
	 * @return the feature slice in the component's package, or {@code null} when unknown
	 */
	public BoundedContextEvent.FeatureSlice resolve ( Class<?> componentClass ) {
		return componentClass == null ? null : byPackage.get(componentClass.getPackageName());
	}

	private static BoundedContextEvent.FeatureSlice toFeatureSlice ( Slice<?> slice ) {
		return new BoundedContextEvent.FeatureSlice(slice.name(), slice.type(), slice.context(), slice.chapter(), slice.tags());
	}

}
