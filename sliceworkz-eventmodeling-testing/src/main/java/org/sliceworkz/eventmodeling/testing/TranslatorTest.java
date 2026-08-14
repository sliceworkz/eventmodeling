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
import org.sliceworkz.eventmodeling.inbound.NoTranslatorRegisteredException;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Base for testing {@link Translator}s: seed domain history if the translation needs any, hand an
 * inbound event to the bounded context, and assert the domain events it raised — synchronously, on
 * the test thread.
 * <pre>{@code
 * class OrderRegisteredTranslatorTest extends TranslatorTest<ShopEvent, PartnerEvent, Void> {
 *
 *     @Override
 *     public List<Translator<PartnerEvent, ShopEvent>> translators ( ) {
 *         return List.of(new OrderRegisteredTranslator());
 *     }
 *
 *     @Test
 *     void aPartnerOrderBecomesADomainOrder ( ) {
 *         given()
 *             .when(new PartnerOrderPlaced("o1", 3))
 *             .then()
 *             .event(new OrderReceived("o1", 3));
 *     }
 * }
 * }</pre>
 * The translators are registered on the bounded context — that is what
 * {@code BoundedContext.translate} consults — and the translation runs through the
 * <em>interactive</em> path: every matching translator runs synchronously in the calling thread,
 * over a real {@code TranslatorContext}, so commands, provided events and idempotency keys behave
 * exactly as deployed. Two properties of that path are asserted for free on every test:
 * <ul>
 * <li><b>The inbound event is not persisted.</b> {@code translate()} is documented not to append it
 * to the inbound stream, and every {@code when(...)} verifies the stream is untouched.</li>
 * <li><b>No matching translator is loud.</b> {@code translate()} throws
 * {@link NoTranslatorRegisteredException}; assert it with {@link TestResult#noTranslatorRegistered()}.</li>
 * </ul>
 *
 * <h2>What this deliberately does not cover</h2>
 * The asynchronous path — {@code incoming(...)}, which appends the inbound event and lets a
 * projector-driven processor run the translators eventually — is a processor-and-bookmark concern of
 * the framework, pinned by its own integration tests. The translation logic is identical on both
 * paths (the same registered {@code Translator} instances run), with one caveat worth knowing: the
 * interactive path matches translators on the inbound event's <em>type</em> only, so a translator
 * whose {@code eventQuery()} also requires tags never matches interactively.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class TranslatorTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	/**
	 * The translators under test, registered on the bounded context. Called once per test method;
	 * return fresh instances.
	 */
	public abstract List<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translators ( );

	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
		translators().forEach(boundedContextBuilder::translator);
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
		return new TestDefinition().given(events);
	}

	public interface TestResult<DOMAIN_EVENT_TYPE> {

		/** Asserts the translation raised exactly {@code events} on the domain stream, in that order. */
		void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events );

		void event ( DOMAIN_EVENT_TYPE event );

		/**
		 * Asserts a single domain event was raised, equal to {@code event}, carrying at least all of
		 * {@code expectedTags} — extra tags (e.g. tracing) are allowed, as in {@code CommandTest}.
		 */
		void event ( DOMAIN_EVENT_TYPE event, Tags expectedTags );

		/** Asserts the translation ran and raised nothing — a matching translator that chose not to. */
		void noEvents ( );

		/** Asserts no registered translator matched the inbound event. */
		void noTranslatorRegistered ( );

		/** Asserts the translation failed, with {@code expectedMessage} as the root cause's message. */
		void error ( String expectedMessage );

	}

	public class TestDefinition {

		private TestResult<DOMAIN_EVENT_TYPE> result;

		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> kernel().event(e));
			return this;
		}

		public TestDefinition events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			return given(events);
		}

		public TestDefinition event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}

		/** Runs every matching registered translator synchronously, capturing what it raised or threw. */
		public TestDefinition when ( INBOUND_EVENT_TYPE inboundEvent ) {
			long inboundEventsBefore = inboundStream().query(EventQuery.matchAll()).count();
			try {
				EventReference bookmark = domainStream().query(EventQuery.matchAll()).reduce((first, second) -> second).map(Event::reference).orElse(null);
				kernel().translate(inboundEvent);
				List<Event<DOMAIN_EVENT_TYPE>> newEvents = new ArrayList<>(domainStream().query(EventQuery.matchAll(), bookmark, Limit.none()).toList());
				this.result = new TestResultImpl(newEvents);
			} catch ( Exception exception ) {
				this.result = new TestResultImpl(exception);
			}
			// part of translate()'s contract, so checked on every test: the interactive path never
			// persists the inbound event, whatever the translation did
			assertEquals(inboundEventsBefore, inboundStream().query(EventQuery.matchAll()).count(),
					"translate() must not persist the inbound event");
			return this;
		}

		public TestResult<DOMAIN_EVENT_TYPE> then ( ) {
			if ( result == null ) {
				fail("result not yet present - has an inbound event even been translated?");
			}
			return result;
		}
	}

	public class TestResultImpl implements TestResult<DOMAIN_EVENT_TYPE> {

		private Exception exception;
		private List<Event<DOMAIN_EVENT_TYPE>> raisedEvents = new ArrayList<>();

		public TestResultImpl ( Exception exception ) {
			this.exception = exception;
		}

		public TestResultImpl ( List<Event<DOMAIN_EVENT_TYPE>> raisedEvents ) {
			this.raisedEvents = raisedEvents;
		}

		@Override
		public void event ( DOMAIN_EVENT_TYPE event ) {
			events(event);
		}

		@Override
		public void event ( DOMAIN_EVENT_TYPE event, Tags expectedTags ) {
			events(event);
			Event<DOMAIN_EVENT_TYPE> actual = raisedEvents.get(0);
			assertTrue(actual.tags().containsAll(expectedTags),
					"event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
		}

		@Override
		public void events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... expectedEvents ) {
			noException();
			assertEquals(expectedEvents.length, raisedEvents.size(), "number of raised domain events not as expected, was " + raisedEvents);
			for ( int i = 0; i < expectedEvents.length; i++ ) {
				assertCompareObjects(expectedEvents[i], raisedEvents.get(i).data(), "domain event #%d".formatted(i));
			}
		}

		@Override
		public void noEvents ( ) {
			noException();
			assertEquals(0, raisedEvents.size(), "no domain events expected, was " + raisedEvents);
		}

		@Override
		public void noTranslatorRegistered ( ) {
			if ( exception == null ) {
				fail("expected no translator to match, but the translation ran and raised " + raisedEvents);
			}
			if ( !(exception instanceof NoTranslatorRegisteredException) ) {
				fail("expected NoTranslatorRegisteredException, got %s (%s)".formatted(exception.getClass().getSimpleName(), exception.getMessage()));
			}
		}

		@Override
		public void error ( String expectedMessage ) {
			if ( exception == null ) {
				fail("exception expected with message '" + expectedMessage + "', got none");
			} else if ( !rootCause(exception).getMessage().equals(expectedMessage) ) {
				fail("exception message (%s) not as expected (%s)".formatted(exception.getMessage(), expectedMessage));
			}
		}

		private void noException ( ) {
			if ( exception != null ) {
				exception.printStackTrace();
				fail("exception (%s) not expected (%s)".formatted(exception.getMessage(), exception));
			}
		}

		private Throwable rootCause ( Throwable t ) {
			return t.getCause() == null ? t : rootCause(t.getCause());
		}
	}

}
