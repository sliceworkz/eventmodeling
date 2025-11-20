/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.slices;

import java.util.Set;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

public interface FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	default String name ( ) {
		String name = this.getClass().getSimpleName();
		if ( name.endsWith("FeatureSlice")) {
			name = name.substring(0, name.length() - "FeatureSlice".length());
		}
		return name;
	}
	
	default FeatureSlice.Type type ( ) {
		return meta().type();
	}
	
	default String chapter ( ) {
		return meta().chapter();
	}
	
	default String context ( ) {
		return meta().context();
	}
	
	default Set<String> tags ( ) {
		return Set.of(meta().tags());
	}
	
	default FeatureSlice meta ( ) {
		return this.getClass().getAnnotation(FeatureSlice.class);
	}

	void configure ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder );
	
}
