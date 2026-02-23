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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

public abstract class CommandTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {
	
	@Override
	public void configure ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder ) {
		// no extra config required to run Commands
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
		return new TestDefinition().given(events);
	}
	
	// TODO should,'t we be able to verify correct Tags on events also?
	public interface TestResult<DOMAIN_EVENT_TYPE> {
		
		void event ( DOMAIN_EVENT_TYPE event );
		
		void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events );

		void error ( String expectedMessage );
		
		void noEvents ( );
		
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
		
}