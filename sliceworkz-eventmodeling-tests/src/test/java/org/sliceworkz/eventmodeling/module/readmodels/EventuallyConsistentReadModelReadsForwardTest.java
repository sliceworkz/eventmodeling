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

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * An eventually consistent read model is projected forward from its bookmark, run after run, so a query
 * asking for anything else is refused at {@code build()}: a backwards read, a limit, a {@code notAfter}
 * and an {@code initQuery} would each make the projector project something other than what the read
 * model declares, and nothing would say so. The same read model registered live may ask for all of
 * them.
 */
public class EventuallyConsistentReadModelReadsForwardTest extends AbstractMockDomainTest {

	private static final EventQuery FIRSTS = EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));

	/** A read model whose queries are what the test hands it. */
	public static class Shaped implements ReadModel<MockDomainEvent> {
		private final String name;
		private final EventQuery eventQuery;
		private final EventQuery initQuery;
		public Shaped ( String name, EventQuery eventQuery, EventQuery initQuery ) { this.name = name; this.eventQuery = eventQuery; this.initQuery = initQuery; }
		public Shaped ( ) { this("live", FIRSTS.backwards().limit(1), EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class)).backwards().limit(1)); }
		@Override public String readmodelName ( ) { return name; }
		@Override public EventQuery eventQuery ( ) { return eventQuery; }
		@Override public EventQuery initQuery ( ) { return initQuery; }
		@Override public void when ( Event<MockDomainEvent> event ) { }
	}

	private void assertRejected ( String name, Function<EventQuery,EventQuery> shape, EventQuery initQuery, String reason ) {
		var builder = builder();
		builder.readmodel(new Shaped(name, shape.apply(FIRSTS), initQuery)).eventuallyConsistent();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains(name), e.getMessage());
		assertTrue(e.getMessage().contains(reason), e.getMessage());
	}

	@Test
	void aBackwardsQueryIsRejected ( ) {
		assertRejected("backwards", EventQuery::backwards, null, "reads backwards");
	}

	@Test
	void aLimitedQueryIsRejected ( ) {
		assertRejected("limited", q -> q.limit(10), null, "is limited");
	}

	@Test
	void aQueryBoundedByNotAfterIsRejected ( ) {
		EventReference somewhere = EventReference.of(EventId.create(), 1L, 1L);
		assertRejected("bounded", q -> q.notAfter(somewhere), null, "notAfter");
	}

	@Test
	void anInitQueryIsRejected ( ) {
		assertRejected("savepoint", q -> q, EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class)).backwards().limit(1), "initQuery()");
	}

	/** Every offender at once, so one build tells you about all of them. */
	@Test
	void allTheOffendersAreNamedTogether ( ) {
		var builder = builder();
		builder.readmodel(new Shaped("one", FIRSTS.backwards(), null)).eventuallyConsistent();
		builder.readmodel(new Shaped("two", FIRSTS.limit(5), null)).eventuallyConsistent();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue(e.getMessage().contains("one") && e.getMessage().contains("two"), e.getMessage());
	}

	/** A filter's own item bounds are business bounds, and a forward read follows them. */
	@Test
	void aForwardQueryOverABoundedFilterIsAccepted ( ) {
		var builder = builder();
		EventReference closed = EventReference.of(EventId.create(), 1L, 1L);
		builder.readmodel(new Shaped("forward", EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class).until(closed)), null)).eventuallyConsistent();

		buildBoundedContext(builder);   // does not throw
	}

	/** Live, the same read model may read backwards, limited, from a savepoint: it is projected per read. */
	@Test
	void theSameShapesAreAcceptedLive ( ) {
		var builder = builder();
		builder.readmodel(Shaped.class).live();

		buildBoundedContext(builder);   // does not throw
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

}
