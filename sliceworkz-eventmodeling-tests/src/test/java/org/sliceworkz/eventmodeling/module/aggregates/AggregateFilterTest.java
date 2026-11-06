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
package org.sliceworkz.eventmodeling.module.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * An aggregate's filter decides both what it reads and what its append checks. Left at its default —
 * every event carrying the identity's tags — it is the classical aggregate, and anything new about it
 * conflicts. Narrowed, the events it leaves out are neither read nor checked: an event it does not read
 * can no longer block its append, whether it was stored before the load or arrived after it.
 */
public class AggregateFilterTest extends AbstractMockDomainTest {

	private static final Tags ORDER = Tags.of("order", "42");

	private EventStream<MockDomainEvent> directStream;
	private Mock domain;

	@BeforeEach
	void wire ( ) {
		this.directStream = EventStore.on(eventStorage()).build()
				.getEventStream(BoundedContextStreams.domain("UnitTestBoundedContext"), MockDomainEvent.class);
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.aggregate(ClassicalAggregate.class);
		builder.aggregate(FirstsOnlyAggregate.class);
		this.domain = buildBoundedContext(builder);
	}

	private void append ( MockDomainEvent event ) {
		directStream.append(Event.of(event, ORDER));
	}

	/** Reads every event of its identity: the default filter. */
	static class ClassicalAggregate implements Aggregate<MockDomainEvent> {
		AggregateContext<MockDomainEvent> context;
		int seen;
		@Override public void setContext ( AggregateContext<MockDomainEvent> context ) { this.context = context; }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
		void record ( ) { context.raiseEvent(new FirstDomainEvent("recorded")); }
	}

	/** Reads only the {@code First}s of its identity: the {@code Second}s change none of its decisions. */
	static class FirstsOnlyAggregate extends ClassicalAggregate {
		@Override public EventFilter eventFilter ( Tags identity ) { return EventFilter.forTypes(FirstDomainEvent.class).tagged(identity); }
	}

	@ForEachBackend
	void theDefaultAggregateReadsAndChecksEverythingAboutItsIdentity ( ) {
		append(new FirstDomainEvent("f1"));
		append(new SecondDomainEvent("s1"));
		ClassicalAggregate aggregate = domain.aggregate(ClassicalAggregate.class, ORDER);
		assertEquals(2, aggregate.seen);

		append(new SecondDomainEvent("s2"));

		assertThrows(OptimisticLockingException.class, aggregate::record, "anything new about it conflicts");
	}

	@ForEachBackend
	void anEventItDoesNotReadStoredBeforeTheLoadDoesNotBlockItsAppend ( ) {
		append(new FirstDomainEvent("f1"));
		append(new SecondDomainEvent("s1"));   // after the last First it reads
		FirstsOnlyAggregate aggregate = domain.aggregate(FirstsOnlyAggregate.class, ORDER);
		assertEquals(1, aggregate.seen);

		aggregate.record();   // does not throw: the check guards what it read, not more
	}

	@ForEachBackend
	void anEventItDoesNotReadArrivingAfterTheLoadDoesNotConflict ( ) {
		append(new FirstDomainEvent("f1"));
		FirstsOnlyAggregate aggregate = domain.aggregate(FirstsOnlyAggregate.class, ORDER);

		append(new SecondDomainEvent("s1"));

		aggregate.record();   // does not throw
	}

	@ForEachBackend
	void anEventItReadsArrivingAfterTheLoadConflicts ( ) {
		append(new FirstDomainEvent("f1"));
		FirstsOnlyAggregate aggregate = domain.aggregate(FirstsOnlyAggregate.class, ORDER);

		append(new FirstDomainEvent("f2"));

		assertThrows(OptimisticLockingException.class, aggregate::record);
	}

}
