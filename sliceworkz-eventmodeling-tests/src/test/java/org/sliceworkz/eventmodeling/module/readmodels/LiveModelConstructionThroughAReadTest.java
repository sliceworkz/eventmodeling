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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * The same rule, seen from where a user meets it: {@code read(X.class, ...)}.
 *
 * <p>{@link LiveModelConstructorSelectionTest} pins the resolution itself; this pins that a read really
 * is constructed through it — a constructor picked correctly and then not used would pass every test
 * there — and that a live read model class no read could ever construct is refused when the context is
 * built rather than when someone first reads it.
 */
public class LiveModelConstructionThroughAReadTest extends AbstractMockDomainTest {

	/**
	 * {@link MockReadModel} declares {@code (String, List)} and {@code (String, ReadModelStorage)}, and
	 * the two build read models that answer differently: one filters on the event types it was given,
	 * the other matches everything. Which one a read gets used to depend on the order
	 * {@code getDeclaredConstructors()} happened to return.
	 */
	@Test
	void aReadIsConstructedThroughTheConstructorItsArgumentsName ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();
		Mock boundedContext = buildBoundedContext(builder);

		boundedContext.event(new FirstDomainEvent("one"));
		boundedContext.event(new SecondDomainEvent("two"));

		MockReadModel filtered = boundedContext.read(MockReadModel.class, "filtered", List.<Class<?>>of(FirstDomainEvent.class));
		MockReadModel everything = boundedContext.read(MockReadModel.class, "everything", ReadModelStorage.EPHEMERAL);

		assertEquals(1, filtered.eventCount(), "(String, List) was asked for, so only the named type is projected");
		assertEquals(2, everything.eventCount(), "(String, ReadModelStorage) was asked for, so everything is");
	}

	/** A read whose arguments fit nothing names the read model, what was passed, and what is on offer. */
	@Test
	void aReadWhoseArgumentsFitNoConstructorSaysSo ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();
		Mock boundedContext = buildBoundedContext(builder);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> boundedContext.read(MockReadModel.class, 42));

		assertTrue(e.getMessage().contains("MockReadModel"), e.getMessage());
		assertTrue(e.getMessage().contains("Integer"), "the argument as passed: " + e.getMessage());
		assertTrue(e.getMessage().contains("MockReadModel(String)"), "and what it declares: " + e.getMessage());
	}

	/**
	 * Being abstract does not depend on what a read passes, so it is refused at build time — the same
	 * trade as a read model registered without a mode, and the reason a build-time check exists at all
	 * beside the resolution above.
	 */
	@Test
	void aLiveReadModelNoReadCouldConstructIsRefusedAtBuildTime ( ) {
		var builder = builder();
		builder.readmodel(HalfAReadModel.class).live();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("HalfAReadModel"), e.getMessage());
		assertTrue(e.getMessage().contains("abstract"), "and says why: " + e.getMessage());
	}

	/**
	 * Two constructors of one arity are <i>not</i> refused at build time: whether they are ambiguous is a
	 * property of the read, and {@code MockReadModel}'s take unrelated types, so no read of it can be.
	 */
	@Test
	void sameArityConstructorsAreNotHeldAgainstAReadModelThatCanBeReadUnambiguously ( ) {
		var builder = builder();
		builder.readmodel(MockReadModel.class).live();

		buildBoundedContext(builder);   // does not throw
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	public abstract static class HalfAReadModel implements ReadModel<MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
		}

	}

}
