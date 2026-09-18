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
package org.sliceworkz.eventmodeling.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The api module is compiled and tested without the implementation, so these run in exactly the
 * situation a consumer is in who forgot the {@code sliceworkz-eventmodeling-impl} dependency.
 */
public class BoundedContextNewBuilderTest {

	interface TestContext extends BoundedContext<String,Integer,Long> { }

	interface ContextWithoutConcreteEventTypes<D,I,O> extends BoundedContext<D,I,O> { }

	@Test
	void aMissingImplementationNamesTheDependencyToAdd ( ) {
		IllegalStateException failure =
			assertThrows(IllegalStateException.class, () -> BoundedContext.newBuilder(TestContext.class));
		String message = failure.getMessage();
		assertTrue(message.contains("sliceworkz-eventmodeling-impl"), message);
		assertTrue(message.contains(BoundedContextBuilder.class.getName()), message);
	}

	@Test
	void unresolvableEventTypesAreStillRejectedBeforeTheLookup ( ) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
			() -> BoundedContext.newBuilder(ContextWithoutConcreteEventTypes.class));
		assertTrue(failure.getMessage().contains("Cannot resolve EventTypes"), failure.getMessage());
	}

}
