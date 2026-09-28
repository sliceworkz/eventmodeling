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
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.RuleViolationException;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;

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

		/**
		 * Asserts the command failed and the <em>root cause</em> of the failure carries
		 * {@code expectedMessage}. Says nothing about the type, so it cannot express the one
		 * distinction WHERE-VALIDATIONS-GO.md asks a command to keep — a business rejection against a
		 * bug. Prefer {@link #businessError(String)} for a rule, and {@link #error(Class, String)}
		 * where another type is meant.
		 */
		void error ( String expectedMessage );

		/**
		 * Asserts the command failed with an exception of {@code expectedType} — the exception a
		 * {@code catch} block in application code would see, not one buried in its cause chain.
		 * <p>
		 * That strictness is the point: a rule judged inside a decision model arrives wrapped by the
		 * projector and is reported by the kernel as a failure rather than as a rejection, so an
		 * assertion that accepted a wrapped {@code BusinessException} would pass for exactly the
		 * shape the framework treats as misplaced. The failure message renders the whole cause
		 * chain, so a wrapped exception is plain to see and can be asserted on by naming the wrapper.
		 */
		void error ( Class<? extends Throwable> expectedType );

		/**
		 * Asserts the command failed with an exception of {@code expectedType} carrying
		 * {@code expectedMessage}. Both halves are about the same exception — the one that came out
		 * of the execution — rather than the type of one and the message of another.
		 */
		void error ( Class<? extends Throwable> expectedType, String expectedMessage );

		/**
		 * Asserts the command rejected the request as a business rule violation:
		 * {@code error(BusinessException.class)}. This is the assertion to reach for on a rule, since
		 * it is the type the kernel reports as {@code CommandRejected} rather than as
		 * {@code CommandFailed} — an {@code IllegalStateException} thrown by the same rule is a bug
		 * by the framework's own taxonomy, and fails here.
		 */
		void businessError ( );

		/** Asserts the command rejected the request with {@code expectedMessage} as the rule's reason. */
		void businessError ( String expectedMessage );

		void noEvents ( );

		/**
		 * Asserts the execution was rejected on its business rules — a {@code RuleViolationException},
		 * thrown by the kernel after the command ran because a violated rule blocks, or needs an override
		 * that was not (sufficiently) requested — and hands over its evaluation for the details:
		 * <pre>{@code
		 * .then().rulesViolated().needsOverrideOf(NO_OVERDRAFT);
		 * }</pre>
		 *
		 * @return assertions on the evaluation the rejection carries
		 */
		EvaluationAssertions rulesViolated ( );

	}

	public interface TestResultWithResponse<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> extends TestResult<DOMAIN_EVENT_TYPE> {

		RESPONSE_TYPE response ( );

	}
	
	public class TestDefinition {
	
		TestResult<DOMAIN_EVENT_TYPE> result;
		Evaluation evaluation;
		Tracing tracing;

		/**
		 * Acts as this actor from here on: every event seeded after this call is appended with the actor
		 * in its tracing (so it carries the {@code x-actor} tag a decision model counting an actor's
		 * overrides queries for), and the command is executed or evaluated for this actor — which is what
		 * {@code CommandContext.actor()} answers.
		 *
		 * @param actor the actor
		 * @return this definition
		 */
		public TestDefinition as ( String actor ) {
			this.tracing = Tracing.actorAndChannel(actor, "test");
			return this;
		}
		
		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e->kernel().event(e));
			return this;
		}
		
		public TestDefinition events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e->kernel().event(e));
			return this;
		}
		
		public TestDefinition event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			if ( tracing != null ) {
				kernel().event(event, tags, tracing);
			} else {
				kernel().event(event, tags);
			}
			return this;
		}

		/**
		 * Evaluates the command instead of executing it — decision models projected, business rules judged,
		 * nothing appended — and fails if anything was appended all the same.
		 * Assert on the outcome with {@link #thenEvaluation()}.
		 *
		 * @param command the command
		 * @return this definition
		 */
		public TestDefinition whenEvaluated ( Command<DOMAIN_EVENT_TYPE> command ) {
			EventReference before = eventStore().getEventStream(eventStreamId(), domainEventType()).head().orElse(null);
			this.evaluation = ( tracing != null ) ? kernel().evaluate(command, tracing) : kernel().evaluate(command);
			EventReference after = eventStore().getEventStream(eventStreamId(), domainEventType()).head().orElse(null);
			assertEquals(before, after, "evaluating a command must not append anything");
			return this;
		}

		/**
		 * @return assertions on the evaluation made by {@link #whenEvaluated(Command)}
		 */
		public EvaluationAssertions thenEvaluation ( ) {
			if ( evaluation == null ) {
				fail("no evaluation present - has the Command been evaluated with whenEvaluated(...)?");
			}
			return new EvaluationAssertions(evaluation);
		}

		public TestDefinition when ( Command<DOMAIN_EVENT_TYPE> command ) {
			try {
				EventReference bookmark = eventStore().getEventStream(eventStreamId(), domainEventType()).head().orElse(null);
				if ( tracing != null ) {
					kernel().execute(command, tracing);
				} else {
					kernel().execute(command);
				}
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), bookmark);
				this.result = new TestResultImpl ( newEvents );
			} catch (Exception exception) {
				this.result = new TestResultImpl ( exception );
			}
			return this;
		}

		public <RESPONSE_TYPE> TestDefinition when ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command ) {
			try {
				EventReference bookmark = eventStore().getEventStream(eventStreamId(), domainEventType()).head().orElse(null);
				CommandExecutionResult<RESPONSE_TYPE> executionResult = ( tracing != null ) ? kernel().execute(command, tracing) : kernel().execute(command);
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = eventStore().getEventStream(eventStreamId(), domainEventType()).query(EventQuery.matchAll(), bookmark);
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
		
		private CaughtError error = CaughtError.of(null);
		private List<Event<DOMAIN_EVENT_TYPE>> producedEvents = new ArrayList<>();
		
		public TestResultImpl ( Exception exception ) {
			this.error = CaughtError.of(exception);
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
			assertEquals(1, producedEvents.size(), () -> "number of events not as expected, produced: " + listing(producedEvents));
			Event<DOMAIN_EVENT_TYPE> actual = producedEvents.get(0);
			assertCompareObjects(event, actual.data(), "event");
			assertTrue(actual.tags().containsAll(expectedTags),
				"event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
		}

		
		@Override
		public void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... expectedEvents ) {
			noException();
			
			assertEquals(expectedEvents.length, producedEvents.size(),
				() -> "number of events not as expected, produced: " + listing(producedEvents));

			for ( int i = 0; i < expectedEvents.length; i++ ) {
				DOMAIN_EVENT_TYPE expected = expectedEvents[i];
				DOMAIN_EVENT_TYPE actual = producedEvents.get(i).data();
				
				assertCompareObjects(expected, actual, "event #%d".formatted(i) );
			}
		}
		
		
		@Override
		public void error ( String expectedMessage ) {
			error.assertMessage(expectedMessage);
		}

		@Override
		public void error ( Class<? extends Throwable> expectedType ) {
			error.assertType(expectedType);
		}

		@Override
		public void error ( Class<? extends Throwable> expectedType, String expectedMessage ) {
			error.assertTypeAndMessage(expectedType, expectedMessage);
		}

		@Override
		public void businessError ( ) {
			error.assertType(BusinessException.class);
		}

		@Override
		public void businessError ( String expectedMessage ) {
			error.assertTypeAndMessage(BusinessException.class, expectedMessage);
		}

		@Override
		public void noEvents() {
			noException();
			assertEquals(0, producedEvents.size(), () -> "no events expected, produced: " + listing(producedEvents));
		}
		
		@Override
		public EvaluationAssertions rulesViolated ( ) {
			error.assertType(RuleViolationException.class);
			return new EvaluationAssertions(((RuleViolationException) error.thrown()).evaluation());
		}

		public void noException ( ) {
			error.assertNone("no failure expected");
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