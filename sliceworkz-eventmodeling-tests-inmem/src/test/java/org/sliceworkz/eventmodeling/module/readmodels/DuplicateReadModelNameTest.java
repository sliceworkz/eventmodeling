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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

public class DuplicateReadModelNameTest extends AbstractMockDomainTest {

	private EventStorage eventStorage;

	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.eventStorage = InMemoryEventStorage.newBuilder().build();
	}

	@AfterEach
	protected void tearDown ( ) {
		if ( boundedContext() != null ) {
			boundedContext().stop();
		}
	}

	@Test
	void duplicateSharedEventuallyConsistentReadModelNamesRejected ( ) {
		var rm1 = new MockReadModel("shared-collision");
		var rm2 = new MockReadModel("shared-collision");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			var builder = baseBuilder();
			builder.readmodel(rm1).shared().eventuallyConsistent();
			builder.readmodel(rm2).shared().eventuallyConsistent();
			builder.build();
		});
		assertEquals("duplicate readmodel name 'shared-collision' - bookmarks would collide", e.getMessage());
	}

	@Test
	void duplicateLocalEventuallyConsistentReadModelNamesRejected ( ) {
		var rm1 = new MockReadModel("local-collision");
		var rm2 = new MockReadModel("local-collision");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			var builder = baseBuilder();
			builder.readmodel(rm1).local().eventuallyConsistent();
			builder.readmodel(rm2).local().eventuallyConsistent();
			builder.build();
		});
		assertEquals("duplicate readmodel name 'local-collision' - bookmarks would collide", e.getMessage());
	}

	@Test
	void sharedAndLocalEventuallyConsistentReadModelsWithSameNameRejected ( ) {
		var sharedRm = new MockReadModel("mixed-collision");
		var localRm = new MockReadModel("mixed-collision");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			var builder = baseBuilder();
			builder.readmodel(sharedRm).shared().eventuallyConsistent();
			builder.readmodel(localRm).local().eventuallyConsistent();
			builder.build();
		});
		assertEquals("duplicate readmodel name 'mixed-collision' - bookmarks would collide", e.getMessage());
	}

	@Test
	void liveAndEventuallyConsistentWithSameNameRejected ( ) {
		// The live model's effective name is its class' simple name ("MockReadModel").
		// Register an EC model that returns that same string from readmodelName(), and expect rejection.
		var collidingEc = new MockReadModel("MockReadModel");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			var builder = baseBuilder();
			builder.readmodel(MockReadModel.class);
			builder.readmodel(collidingEc).shared().eventuallyConsistent();
			builder.build();
		});
		assertEquals("duplicate readmodel name 'MockReadModel' - bookmarks would collide", e.getMessage());
	}

	@Test
	void distinctNamesBuildSuccessfully ( ) {
		var rm1 = new MockReadModel("rm-alpha");
		var rm2 = new MockReadModel("rm-beta");
		var rm3 = new MockReadModel("rm-gamma");

		var builder = baseBuilder();
		builder.readmodel(rm1).shared().eventuallyConsistent();
		builder.readmodel(rm2).local().eventuallyConsistent();
		builder.readmodel(rm3).shared().eventuallyConsistent();
		Mock ctx = buildBoundedContext(builder);
		assertNotNull(ctx);
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));
	}
}
