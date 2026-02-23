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
package org.sliceworkz.eventmodeling.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.query.EventQuery;

public class ReadModelTest {

	@Test
	void testReadModelName ( ) {
		assertEquals("DummyReadModel", new DummyReadModel().readmodelName());
		assertEquals("AnotherName", new DummyReadModelOverridingName().readmodelName());
	}
	
	class DummyReadModel implements ReadModel<Object> {

		@Override
		public EventQuery eventQuery() {
			return null;
		}

		@Override
		public void when(Object event) {
			
		}
		
	}
	
	class DummyReadModelOverridingName implements ReadModel<Object> {

		@Override
		public String readmodelName ( ) {
			return "AnotherName";
		}
		
		@Override
		public EventQuery eventQuery() {
			return null;
		}

		@Override
		public void when(Object event) {
			
		}
		
	}

}
