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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * {@code translator(Class)} and {@code dispatcher(Class)} construct the component themselves, so every
 * way that can fail is a property of the class registered — and each one is a registration mistake a
 * caller has to be able to find.
 * <p>
 * Each is an {@link IllegalArgumentException} naming the class, the kind it was registered as and the
 * remedy, like every other registration rejection on this builder. The alternative -- a bare
 * {@code RuntimeException} around each reflective exception -- loses because such a failure carries no
 * message at all, and buries a throwing constructor's own exception a cause deeper still.
 */
public class RegisteringByClassTest extends AbstractMockDomainTest {

	@Test
	void aPublicClassWithANoArgumentConstructorIsRegistered ( ) {
		Mock ctx = buildBoundedContext(
			baseBuilder()
				.translator(PublicTranslator.class)
				.dispatcher(PublicDispatcher.class)
		);
		assertNotNull(ctx);
	}

	/**
	 * The ordinary one: a component that takes constructor arguments cannot be registered by class, and
	 * the remedy — register an instance — is what the message has to say.
	 */
	@Test
	void aClassWithoutANoArgumentConstructorIsRefused ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().translator(TranslatorTakingArguments.class));

		assertTrue(e.getMessage().contains(TranslatorTakingArguments.class.getName()),
				"the message should name the class: " + e.getMessage());
		assertTrue(e.getMessage().contains("no-argument constructor"),
				"the message should name the reason: " + e.getMessage());
		assertTrue(e.getMessage().contains("register an instance"),
				"the message should name the remedy: " + e.getMessage());
	}

	/** An interface or an abstract base registered where an implementation was meant. */
	@Test
	void anAbstractClassIsRefused ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().dispatcher(AbstractDispatcher.class));

		assertTrue(e.getMessage().contains(AbstractDispatcher.class.getName()),
				"the message should name the class: " + e.getMessage());
		assertTrue(e.getMessage().contains("concrete"),
				"the message should name the reason: " + e.getMessage());
	}

	/**
	 * A no-argument constructor the framework cannot reach is as good as no constructor at all, and is
	 * told apart from one only by the reflective cause — so the message names both possibilities. A
	 * private constructor is the probe here because it is out of reach whatever package the caller sits
	 * in: package-private would be reachable from the framework's own package, which this test happens
	 * to share.
	 */
	@Test
	void aConstructorTheFrameworkCannotReachIsRefused ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().translator(TranslatorWithAPrivateConstructor.class));

		assertTrue(e.getMessage().contains(TranslatorWithAPrivateConstructor.class.getName()),
				"the message should name the class: " + e.getMessage());
		assertTrue(e.getMessage().contains("public no-argument constructor"),
				"the message should name the reason: " + e.getMessage());
	}

	/**
	 * A constructor that ran and threw is a different mistake from one that could not be called, and the
	 * thing worth reading is what it threw — so that is the cause, not the {@code InvocationTargetException}
	 * the reflection wrapped it in.
	 */
	@Test
	void aConstructorThatThrewReportsWhatItThrew ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().dispatcher(ThrowingDispatcher.class));

		assertTrue(e.getMessage().contains(ThrowingDispatcher.class.getName()),
				"the message should name the class: " + e.getMessage());
		assertTrue(e.getMessage().contains("threw from its no-argument constructor"),
				"the message should say the constructor ran: " + e.getMessage());
		assertSame(ThrowingDispatcher.BOOM, e.getCause(),
				"the cause should be what the constructor threw, not the reflective wrapper");
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private static EventQuery everything ( ) {
		return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
	}

	public static class PublicTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return everything();
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// no-op for the test
		}
	}

	public static class PublicDispatcher implements Dispatcher<MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return everything();
		}

		@Override
		public void when ( Event<MockOutboundEvent> event ) {
			// no-op for the test
		}
	}

	public static class TranslatorTakingArguments extends PublicTranslator {

		public TranslatorTakingArguments ( String somethingItNeeds ) {
			// the ordinary shape of a component that has to be registered as an instance
		}
	}

	public abstract static class AbstractDispatcher extends PublicDispatcher { }

	public static class TranslatorWithAPrivateConstructor extends PublicTranslator {

		private TranslatorWithAPrivateConstructor ( ) { }
	}

	public static class ThrowingDispatcher extends PublicDispatcher {

		static final IllegalStateException BOOM = new IllegalStateException("the dispatcher's constructor failed");

		public ThrowingDispatcher ( ) {
			throw BOOM;
		}
	}
}
