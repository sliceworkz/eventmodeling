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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * How a read model is projected has to be said out loud.
 *
 * <p>The two modes are not interchangeable: one is projected per read and is always current, the other
 * in the background and is behind by up to a poll interval, and every caller of that read model lives
 * with the difference. Which one applies used to follow silently from the overload — a class was live,
 * an instance was eventually consistent — so a registration could be written without the choice ever
 * being made, and read afterwards without it being visible.
 *
 * <p>The registration itself stays eager, which is the part worth being careful about: registering
 * lazily from {@code live()} would turn a forgotten verb into a read model that silently is not there,
 * surfacing as a failing read rather than as the build-time complaint below. The read model is
 * registered as it always was; what is now required is the statement of intent.
 */
public class ReadModelModeIsExplicitTest extends AbstractMockDomainTest {

	@Test
	void aLiveModelRegisteredWithoutSayingSoIsRejected ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("MockReadModel"), e.getMessage());
		assertTrue(e.getMessage().contains(".live()"), "and the message says which verb was missing: " + e.getMessage());
	}

	@Test
	void anEventuallyConsistentReadModelRegisteredWithoutSayingSoIsRejected ( ) {
		var builder = builder();
		builder.readmodel(new MockReadModel("undeclared", ReadModelStorage.EPHEMERAL));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("undeclared"), e.getMessage());
		assertTrue(e.getMessage().contains(".eventuallyConsistent()"), "and the message says which verb was missing: " + e.getMessage());
	}

	/** Every offender at once, so one build tells you about all of them rather than one per attempt. */
	@Test
	void allTheUndeclaredOnesAreNamedTogether ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class);
		builder.readmodel(new MockReadModel("also undeclared", ReadModelStorage.EPHEMERAL));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("MockReadModel"), e.getMessage());
		assertTrue(e.getMessage().contains("also undeclared"), e.getMessage());
	}

	@Test
	void sayingSoIsAllItTakes ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();
		builder.readmodel(new MockReadModel("declared", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();

		buildBoundedContext(builder);   // does not throw
	}

	/**
	 * Asking for snapshots is itself a statement that the read model is projected per read, so it needs
	 * no {@code live()} after it — and a terminal on the snapshot specification is optional as it
	 * always was.
	 */
	@Test
	void askingForSnapshotsSaysItToo ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).snapshots(new SnapshotStorage<Object>() {

			@Override
			public Optional<SnapshotRecord<Object>> load ( String key, String version ) {
				return Optional.empty();
			}

			@Override
			public void save ( String key, String version, Object snapshot, EventReference eventReference ) {
			}

		});

		buildBoundedContext(builder);   // does not throw
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

}
