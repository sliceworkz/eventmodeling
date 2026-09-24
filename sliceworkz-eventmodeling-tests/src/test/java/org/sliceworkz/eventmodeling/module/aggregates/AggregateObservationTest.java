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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver.Recording;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Loading an aggregate and appending what it raised are observed: the load with the identity and the
 * caller's tracing, answering how much it replayed; the append with what it raised per type, answering
 * what was stored — or {@link Outcome.Conflicted} when another instance of the same aggregate got there
 * first, which is an answer, not a failure.
 */
public class AggregateObservationTest extends AbstractMockDomainTest {

	private final RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();

	@Test
	void aLoadIsObservedOnceWithItsIdentityAndWhatItReplayed ( ) {
		Mock domain = domainWithAggregate();
		domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123")).doSomething();

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests")).actor("alice").channel("web");
		domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"), tracing);

		Recording load = observer.last(Observation.AggregateLoad.class);
		Observation.AggregateLoad started = load.observation(Observation.AggregateLoad.class);
		assertEquals("MockAggregate", started.aggregate());
		assertEquals(Tags.of("businessObject", "123"), started.identity());
		assertEquals("web", started.tracing().channel());
		Outcome.AggregateLoaded loaded = load.outcome(Outcome.AggregateLoaded.class);
		assertEquals(1, loaded.eventsStreamed());
		assertEquals(Optional.empty(), loaded.startedAfter());
		assertEquals(2, observer.recordings(Observation.AggregateLoad.class).size(), "one observation per load");
		assertEquals(List.of(), observer.violations());
	}

	@Test
	void anAppendIsObservedWithWhatWasRaisedAndStored ( ) {
		Mock domain = domainWithAggregate();
		MockAggregate aggregate = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

		aggregate.doThreeThings();

		Recording append = observer.last(Observation.AggregateAppend.class);
		Observation.AggregateAppend started = append.observation(Observation.AggregateAppend.class);
		assertEquals("MockAggregate", started.aggregate());
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 3), started.raisedPerType());
		assertEquals(3, append.outcome(Outcome.Appended.class).appended().size());
		assertEquals(List.of(), observer.violations());
	}

	@Test
	void aStaleAppendAnswersConflicted ( ) {
		Mock domain = domainWithAggregate();
		MockAggregate first = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		MockAggregate second = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

		first.doSomething();
		assertThrows(OptimisticLockingException.class, () -> second.doSomething());

		Recording stale = observer.last(Observation.AggregateAppend.class);
		stale.outcome(Outcome.Conflicted.class);
		assertSame(null, stale.failure().orElse(null), "a conflict is an answer, not a failure");
		assertEquals(List.of(), observer.violations());
	}

	private Mock domainWithAggregate ( ) {
		return buildBoundedContext(
				BoundedContext.newBuilder(Mock.class)
					.name("UnitTestBoundedContext")
					.eventStorage(eventStorage())
					.observer(observer)
					.instance(InstanceFactory.determine("unittests"))
					.aggregate(MockAggregate.class)
					.done());
	}

}
