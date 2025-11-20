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
package org.sliceworkz.eventmodeling.domain;

import org.sliceworkz.eventstore.events.Tag;

public class DomainConceptTag {
	
	public static Tag of ( DomainConcept key, DomainConceptId value ) {
		return of(key==null?null:key.name(), value==null?null:value.value());
	}
	
	public static Tag of ( DomainConcept key ) {
		return of(key.name(), null);
	}

	public static Tag of ( DomainConcept key, String value ) {
		return of(key.name(), value);
	}
	
	public static Tag of ( String key, String value ) {
		return Tag.of(key, value);
	}

}
