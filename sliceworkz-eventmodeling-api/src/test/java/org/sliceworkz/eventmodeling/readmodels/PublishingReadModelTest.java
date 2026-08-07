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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A read model that mutates its own fields from {@code when} is being read, from another thread,
 * half-way through every batch — and the position it has reached is not something a reader can ask
 * for at the same moment as the state. This class exists so that neither is true: a batch is folded
 * aside and becomes visible as one immutable {@code (state, position)} pair.
 *
 * <p>These tests pin the batch lifecycle that guarantee rests on. The concurrency itself is not
 * tested here — a passing race is not evidence of anything — it follows from the state being
 * published through a single volatile field and never mutated afterwards, which is what the
 * assertions below are really about.
 */
class PublishingReadModelTest {

	@Test
	void aReadModelThatHasProjectedNothingIsAtItsInitialStateAndNoPosition ( ) {
		Counter counter = new Counter();

		assertEquals(0, counter.state().intValue());
		assertNull(counter.upTo(), "no event has been folded, so there is no position to report");
	}

	@Test
	void aCommittedBatchPublishesItsStateAndItsPositionTogether ( ) {
		Counter counter = new Counter();

		EventReference last = project(counter, 1, 2, 3);

		ReadModelResult<Integer> published = counter.published();
		assertEquals(3, published.data().intValue());
		assertEquals(last, published.upTo(), "the position is the last event of the batch it published");
	}

	/**
	 * The reader's whole safety argument: what it took is a value that no later batch can alter under
	 * it. A batch replaces the published pair, it never edits it.
	 */
	@Test
	void aPublishedStateIsNotChangedByTheNextBatch ( ) {
		Counter counter = new Counter();
		project(counter, 1, 2);

		ReadModelResult<Integer> taken = counter.published();
		project(counter, 3, 4);

		assertEquals(2, taken.data().intValue(), "the pair taken earlier still describes the moment it was taken");
		assertEquals(4, counter.state().intValue(), "while the read model has moved on");
	}

	/**
	 * A batch that did not commit did not happen. The events it folded are offered again on the next
	 * run, so publishing half of them would double-count them.
	 */
	@Test
	void aCancelledBatchPublishesNothing ( ) {
		Counter counter = new Counter();
		EventReference committed = project(counter, 1, 2);
		ReadModelResult<Integer> before = counter.published();

		counter.beforeBatch();
		counter.when(event(3));
		counter.when(event(4));
		counter.cancelBatch();

		assertEquals(2, counter.state().intValue(), "the state is the one the last committed batch published");
		assertEquals(committed, counter.upTo());
		assertSame(before, counter.published(), "and it is literally the same pair -- nothing was republished");
	}

	/**
	 * The projector reads past events this read model does not handle. That says nothing about state
	 * it does not hold, so its position stays where its own last event left it — reporting the
	 * projector's reading position instead would claim the model reflects events it never saw.
	 */
	@Test
	void aBatchWithNoEventsOfOursLeavesThePositionWhereItWas ( ) {
		Counter counter = new Counter();
		EventReference last = project(counter, 1, 2);

		counter.beforeBatch();
		counter.afterBatch(Optional.empty());

		assertEquals(2, counter.state().intValue());
		assertEquals(last, counter.upTo());
	}

	@Test
	void batchesAccumulate ( ) {
		Counter counter = new Counter();

		project(counter, 1, 2);
		project(counter, 3);
		project(counter, 4, 5);

		assertEquals(5, counter.state().intValue(), "each batch folds onto what the previous one published");
	}

	/**
	 * {@code initialState()} is asked for lazily rather than from a constructor, so that a subclass can
	 * build it out of its own fields — which a superclass constructor would run before those fields are
	 * assigned, handing the read model a state built from nulls.
	 */
	@Test
	void theInitialStateIsBuiltFromTheSubclassOwnFields ( ) {
		StartingAt counter = new StartingAt(100);

		assertEquals(100, counter.state().intValue());
	}

	// -- helpers --

	private EventReference project ( PublishingReadModel<TestEvent,Integer> model, int... positions ) {
		model.beforeBatch();
		EventReference last = null;
		for ( int position : positions ) {
			Event<TestEvent> e = event(position);
			model.when(e);
			last = e.reference();
		}
		model.afterBatch(Optional.ofNullable(last));
		return last;
	}

	private static Event<TestEvent> event ( int position ) {
		TestEvent data = new TestEvent("event " + position);
		return Event.of(
				EventStreamId.forContext("test"),
				EventReference.of(EventId.create(), position, position),
				EventType.of(data),
				EventType.of(data),
				data,
				Tags.none(),
				LocalDateTime.now(ZoneOffset.UTC));
	}

	record TestEvent ( String value ) { }

	/** The whole of a publishing read model: a state, where it starts, and a fold. */
	static class Counter extends PublishingReadModel<TestEvent,Integer> {

		@Override
		protected Integer initialState ( ) {
			return 0;
		}

		@Override
		protected Integer apply ( Integer state, Event<TestEvent> event ) {
			return state + 1;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

	}

	static class StartingAt extends Counter {

		private final int start;

		StartingAt ( int start ) {
			this.start = start;
		}

		@Override
		protected Integer initialState ( ) {
			return start;
		}

	}

}
