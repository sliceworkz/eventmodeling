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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

public abstract class CommandTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {
	
	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
		// no extra config required to run Commands
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
		return new TestDefinition().given(events);
	}
	
	public interface TestResult<DOMAIN_EVENT_TYPE> {

		void event ( DOMAIN_EVENT_TYPE event );

		/**
		 * Asserts a single event was produced, equal to {@code event}, and that it carries (at least)
		 * all of {@code expectedTags}. Extra tags on the produced event (e.g. tracing tags added by the
		 * kernel) are allowed, so the assertion stays focused on the domain tags the command must raise.
		 */
		void event ( DOMAIN_EVENT_TYPE event, Tags expectedTags );

		void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events );

		void error ( String expectedMessage );

		void noEvents ( );

	}

	public interface TestResultWithResponse<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> extends TestResult<DOMAIN_EVENT_TYPE> {

		RESPONSE_TYPE response ( );

	}
	
	public class TestDefinition {
	
		TestResult<DOMAIN_EVENT_TYPE> result;
		
		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e->kernel().event(e));
			return this;
		}
		
		public TestDefinition events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e->kernel().event(e));
			return this;
		}
		
		public TestDefinition event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}

		public TestDefinition when ( Command<DOMAIN_EVENT_TYPE> command ) {
			try {
				EventReference bookmark = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), null, Limit.none()).reduce((first, second)->second).map(Event::reference).orElse(null);
				kernel().execute(command);
				@SuppressWarnings("unchecked")
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), bookmark, Limit.none()).map(e->(Event<DOMAIN_EVENT_TYPE>)e).toList();
				this.result = new TestResultImpl ( newEvents );
			} catch (Exception exception) {
				this.result = new TestResultImpl ( exception );
			}
			return this;
		}

		public <RESPONSE_TYPE> TestDefinition when ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command ) {
			try {
				EventReference bookmark = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), null, Limit.none()).reduce((first, second)->second).map(Event::reference).orElse(null);
				CommandExecutionResult<RESPONSE_TYPE> executionResult = kernel().execute(command);
				@SuppressWarnings("unchecked")
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), bookmark, Limit.none()).map(e->(Event<DOMAIN_EVENT_TYPE>)e).toList();
				this.result = new TestResultWithResponseImpl<> ( newEvents, executionResult.response() );
			} catch (Exception exception) {
				this.result = new TestResultImpl ( exception );
			}
			return this;
		}

		public TestResult<DOMAIN_EVENT_TYPE> then ( ) {
			if ( result == null ) {
				fail("result not yet present - has Command even been executed?");
			}
			return result;
		}
		
	}
	
	public class TestResultImpl implements TestResult<DOMAIN_EVENT_TYPE> {
		
		private Exception exception;
		private List<Event<DOMAIN_EVENT_TYPE>> producedEvents = new ArrayList<>();
		
		public TestResultImpl ( Exception exception ) {
			this.exception = exception;
		}
		
		public TestResultImpl ( List<Event<DOMAIN_EVENT_TYPE>> producedEvents ) {
			this.producedEvents = producedEvents;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void event(DOMAIN_EVENT_TYPE event) {
			events(event);
		}

		@Override
		public void event(DOMAIN_EVENT_TYPE event, Tags expectedTags) {
			noException();
			assertEquals(1, producedEvents.size(), "number of events not as expected");
			Event<DOMAIN_EVENT_TYPE> actual = producedEvents.get(0);
			assertCompareObjects(event, actual.data(), "event");
			assertTrue(actual.tags().containsAll(expectedTags),
				"event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
		}

		
		@Override
		public void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... expectedEvents ) {
			noException();
			
			if ( expectedEvents.length != producedEvents.size() ) {
				System.out.println("PRODUCED EVENTS: ");
				producedEvents.forEach(System.out::println);
			}
			assertEquals(expectedEvents.length, producedEvents.size(), "number of events not as expected");

			for ( int i = 0; i < expectedEvents.length; i++ ) {
				DOMAIN_EVENT_TYPE expected = expectedEvents[i];
				DOMAIN_EVENT_TYPE actual = producedEvents.get(i).data();
				
				assertCompareObjects(expected, actual, "event #%d".formatted(i) );
			}
		}
		
		
		@Override
		public void error ( String expectedMessage ) {
			if (exception==null) {
				fail("exception expected with message '" + expectedMessage + "', got none");
			} else if ( ! rootCause(exception).getMessage().equals(expectedMessage) ){
				fail("exception message (%s) not as expected (%s)".formatted(exception.getMessage(), expectedMessage));
			}
		}

		@Override
		public void noEvents() {
			noException();
			if ( producedEvents.size() > 0 ) {
				// first print them, so we have an idea what was raised
				producedEvents.forEach(System.out::println);
			}
			assertEquals(0, producedEvents.size(), "no events expected");
		}
		
		public void noException ( ) {
			if (exception!=null) {
				exception.printStackTrace();
				fail("exception (%s) not expected (%s)".formatted(exception.getMessage(), exception));
			}
		}
		
		Throwable rootCause ( Throwable t ) {
			if ( t.getCause() == null ) {
				return t;
			} else {
				return rootCause ( t.getCause() );
			}
		}
	}

	public class TestResultWithResponseImpl<RESPONSE_TYPE> extends TestResultImpl implements TestResultWithResponse<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> {

		private final RESPONSE_TYPE response;

		public TestResultWithResponseImpl ( List<Event<DOMAIN_EVENT_TYPE>> producedEvents, RESPONSE_TYPE response ) {
			super(producedEvents);
			this.response = response;
		}

		@Override
		public RESPONSE_TYPE response ( ) {
			return response;
		}
	}

}