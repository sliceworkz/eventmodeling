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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Base class for testing Aggregates.
 * <p>
 * Usage example:
 * <pre>{@code
 * class MyAggregateTest extends AggregateTest<DomainEvent, Void, Void> {
 *
 *     @Override
 *     public Class<MyAggregate> getAggregateClass() {
 *         return MyAggregate.class;
 *     }
 *
 *     @Test
 *     void testAggregateMethod() {
 *         given(Tags.of("id", "123"))
 *             .events(new SomeEvent("initial"))
 *             .when(aggregate -> aggregate.doSomething())
 *             .then()
 *             .event(new ExpectedEvent("result"));
 *     }
 * }
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type
 * @param <INBOUND_EVENT_TYPE> the inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the outbound event type
 */
public abstract class AggregateTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE, AGGREGATE extends Aggregate<DOMAIN_EVENT_TYPE>>
		extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Returns the aggregate class under test.
	 */
	public abstract Class<AGGREGATE> getAggregateClass();

	@Override
	public void configure(BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder) {
		boundedContextBuilder.aggregate(getAggregateClass());
	}

	/**
	 * Starts a test definition with the given aggregate identity.
	 *
	 * @param identity the Tags identifying the aggregate instance
	 * @return a TestDefinition to continue building the test
	 */
	public TestDefinition given(Tags identity) {
		return new TestDefinition(identity);
	}

	/**
	 * Interface for verifying test results.
	 */
	public interface TestResult<DOMAIN_EVENT_TYPE> {

		/**
		 * Verifies that exactly one event was raised.
		 */
		void event(DOMAIN_EVENT_TYPE event);

		/**
		 * Verifies that the specified events were raised in order.
		 */
		void events(@SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events);

		/**
		 * Verifies that an error occurred with the expected message.
		 */
		void error(String expectedMessage);

		/**
		 * Verifies that no events were raised.
		 */
		void noEvents();
	}

	/**
	 * Builder class for defining aggregate tests.
	 */
	public class TestDefinition {

		private final Tags identity;
		private TestResult<DOMAIN_EVENT_TYPE> result;

		public TestDefinition(Tags identity) {
			this.identity = identity;
		}

		/**
		 * Sets up prior events for the aggregate.
		 *
		 * @param events the events to apply before the test action
		 * @return this TestDefinition for chaining
		 */
		public TestDefinition events(@SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events) {
			Arrays.asList(events).forEach(e -> kernel().event(e, identity));
			return this;
		}

		/**
		 * Sets up a single prior event with custom tags.
		 *
		 * @param event the event to apply
		 * @param tags the tags for the event
		 * @return this TestDefinition for chaining
		 */
		public TestDefinition event(DOMAIN_EVENT_TYPE event, Tags tags) {
			kernel().event(event, tags);
			return this;
		}

		/**
		 * Executes an action on the aggregate.
		 *
		 * @param action the action to perform on the aggregate
		 * @return this TestDefinition for chaining
		 */
		public TestDefinition when(Consumer<AGGREGATE> action) {
			try {
				// Get a bookmark before executing the action
				EventReference bookmark = eventStore()
						.getEventStream(eventStreamId(), domainEventType())
						.query(EventQuery.matchAll(), null, Limit.none())
						.reduce((first, second) -> second)
						.map(Event::reference)
						.orElse(null);

				// Load the aggregate and execute the action
				AGGREGATE aggregate = kernel().aggregate(getAggregateClass(), identity);
				action.accept(aggregate);

				// Collect events raised after the action
				@SuppressWarnings("unchecked")
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = eventStore()
						.getEventStream(eventStreamId(), domainEventType())
						.query(EventQuery.matchAll(), bookmark, Limit.none())
						.map(e -> (Event<DOMAIN_EVENT_TYPE>) e)
						.toList();

				this.result = new TestResultImpl(newEvents);
			} catch (Exception exception) {
				this.result = new TestResultImpl(exception);
			}
			return this;
		}

		/**
		 * Returns the test result for verification.
		 *
		 * @return the TestResult to verify outcomes
		 */
		public TestResult<DOMAIN_EVENT_TYPE> then() {
			if (result == null) {
				fail("result not yet present - has the aggregate action been executed?");
			}
			return result;
		}
	}

	/**
	 * Implementation of TestResult for verifying aggregate test outcomes.
	 */
	public class TestResultImpl implements TestResult<DOMAIN_EVENT_TYPE> {

		private Exception exception;
		private List<Event<DOMAIN_EVENT_TYPE>> producedEvents = new ArrayList<>();

		public TestResultImpl(Exception exception) {
			this.exception = exception;
		}

		public TestResultImpl(List<Event<DOMAIN_EVENT_TYPE>> producedEvents) {
			this.producedEvents = producedEvents;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void event(DOMAIN_EVENT_TYPE event) {
			events(event);
		}

		@Override
		public void events(@SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... expectedEvents) {
			noException();

			if (expectedEvents.length != producedEvents.size()) {
				System.out.println("PRODUCED EVENTS: ");
				producedEvents.forEach(System.out::println);
			}
			assertEquals(expectedEvents.length, producedEvents.size(), "number of events not as expected");

			for (int i = 0; i < expectedEvents.length; i++) {
				DOMAIN_EVENT_TYPE expected = expectedEvents[i];
				DOMAIN_EVENT_TYPE actual = producedEvents.get(i).data();

				assertCompareObjects(expected, actual, "event #%d".formatted(i));
			}
		}

		@Override
		public void error(String expectedMessage) {
			if (exception == null) {
				fail("exception expected with message '" + expectedMessage + "', got none");
			} else if (!rootCause(exception).getMessage().equals(expectedMessage)) {
				fail("exception message (%s) not as expected (%s)".formatted(exception.getMessage(), expectedMessage));
			}
		}

		@Override
		public void noEvents() {
			noException();
			if (producedEvents.size() > 0) {
				// first print them, so we have an idea what was raised
				producedEvents.forEach(System.out::println);
			}
			assertEquals(0, producedEvents.size(), "no events expected");
		}

		public void noException() {
			if (exception != null) {
				exception.printStackTrace();
				fail("exception (%s) not expected (%s)".formatted(exception.getMessage(), exception));
			}
		}

		Throwable rootCause(Throwable t) {
			if (t.getCause() == null) {
				return t;
			} else {
				return rootCause(t.getCause());
			}
		}
	}
}
