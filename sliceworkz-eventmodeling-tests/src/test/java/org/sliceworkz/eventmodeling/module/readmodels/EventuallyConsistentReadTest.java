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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * {@code read(X.class)} answers the registered instance of an eventually consistent read model.
 *
 * <p>That is how the slices of one context share a read model one of them registers: the others read it
 * by its class, as they read a live one, and get the one instance its projector keeps current rather
 * than a projection made for the read. The rules around it are what keep that answer unambiguous: an
 * instance registered from two aspects is one read model, and a read that cannot tell which answer is
 * meant — a class registered under two names, parameters for an instance that already exists, no
 * parameters for a class registered both live and eventually consistent — is refused.
 */
public class EventuallyConsistentReadTest extends AbstractMockDomainTest {

	@ForEachBackend
	void aReadByClassAnswersTheRegisteredInstanceAsItsProjectorKeepsIt ( ) {
		MockReadModel shared = new MockReadModel("shared", ReadModelStorage.EPHEMERAL);
		var builder = builder();
		builder.readmodel(shared).eventuallyConsistent();
		Mock mock = buildBoundedContext(builder);

		mock.event(new MockDomainEvent.FirstDomainEvent("one"));
		mock.event(new MockDomainEvent.SecondDomainEvent("two"));

		MockReadModel read = mock.read(MockReadModel.class);
		assertSame(shared, read, "the registered instance, not one projected for the read");
		waitBecauseOfEventualConsistency(() -> mock.read(MockReadModel.class).eventCount() == 2);
	}

	/**
	 * A slice registers the read model its endpoints read from configureCommand and configureQuery, so it
	 * exists wherever either aspect runs; an instance running both makes both registrations, of one
	 * instance, which is one read model with one projector — not a duplicate bookmark name.
	 */
	@Test
	void theSameInstanceRegisteredTwiceIsOneReadModel ( ) {
		MockReadModel shared = new MockReadModel("shared", ReadModelStorage.EPHEMERAL);
		var builder = builder();
		builder.readmodel(shared).eventuallyConsistent();
		builder.readmodel(shared).eventuallyConsistent();
		Mock mock = buildBoundedContext(builder);

		long projectors = mock.processors().stream().filter(p -> p.kind() == ProcessorKind.READ_MODEL).count();
		assertEquals(1, projectors, "one projector for the one instance");
		assertSame(shared, mock.read(MockReadModel.class));
	}

	@Test
	void twoInstancesUnderOneNameStayRefused ( ) {
		var builder = builder();
		builder.readmodel(new MockReadModel("shared", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.readmodel(new MockReadModel("shared", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("shared"), e.getMessage());
	}

	@Test
	void aClassRegisteredUnderSeveralNamesIsAmbiguousToARead ( ) {
		var builder = builder();
		builder.readmodel(new MockReadModel("first", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.readmodel(new MockReadModel("second", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock mock = buildBoundedContext(builder);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mock.read(MockReadModel.class));

		assertTrue(e.getMessage().contains("first") && e.getMessage().contains("second"), "names what it could not choose between: " + e.getMessage());
	}

	@Test
	void parametersForAnEventuallyConsistentReadModelAreRefused ( ) {
		var builder = builder();
		builder.readmodel(new MockReadModel("shared", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock mock = buildBoundedContext(builder);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mock.read(MockReadModel.class, "someLiveModel"));

		assertTrue(e.getMessage().contains("without parameters"), e.getMessage());
	}

	/**
	 * Without parameters the read could mean the projection made for it or the instance its projector
	 * keeps, each with its own freshness, so it is refused; with parameters it is the live read it always
	 * was, which is why registering one class both ways stays allowed.
	 */
	@Test
	void aClassRegisteredBothWaysIsReadLiveWithParametersAndRefusedWithout ( ) {
		MockReadModel shared = new MockReadModel("named-apart", ReadModelStorage.EPHEMERAL);
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();
		builder.readmodel(shared).eventuallyConsistent();
		Mock mock = buildBoundedContext(builder);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mock.read(MockReadModel.class));
		assertTrue(e.getMessage().contains("both live and eventually consistent"), e.getMessage());

		MockReadModel live = mock.read(MockReadModel.class, "someLiveModel");
		assertTrue(live != shared, "with parameters it is projected live");
		assertEquals("someLiveModel", live.name());
	}

	@Test
	void aLiveReadIsUnchanged ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();
		Mock mock = buildBoundedContext(builder);

		mock.event(new MockDomainEvent.FirstDomainEvent("one"));

		MockReadModel first = mock.read(MockReadModel.class, "someLiveModel");
		MockReadModel second = mock.read(MockReadModel.class, "someLiveModel");
		assertEquals(1, first.eventCount());
		assertTrue(first != second, "a live read projects a fresh instance every time");
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

}
