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
package org.sliceworkz.eventmodeling.module.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.NoTranslatorRegisteredException;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class TranslateTest extends AbstractMockDomainTest {

	private EventStorage eventStorage;

	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.eventStorage = InMemoryEventStorage.newBuilder().build();
	}

	@AfterEach
	protected void tearDown ( ) {
		if ( boundedContext() != null ) {
			boundedContext().stop();
		}
	}

	@Test
	void translateRunsMatchingTranslatorSynchronouslyAndBypassesInboundStream ( ) {
		Mock ctx = buildBoundedContext(baseBuilder().translator(new SomeInboundTranslator()));

		int inboundBefore = inboundStream().query(EventQuery.matchAll()).toList().size();
		int domainBefore = domainStream().query(EventQuery.matchAll()).toList().size();

		List<EventReference> raised = ctx.translate(new SomeInboundEvent("hello"));

		// the matching translator raised exactly one domain event and we got its reference back
		assertEquals(1, raised.size());
		assertNotNull(raised.get(0));

		// the interactive path does NOT append to the inbound stream ...
		assertEquals(inboundBefore, inboundStream().query(EventQuery.matchAll()).toList().size());

		// ... but the raised domain event IS persisted synchronously
		List<MockDomainEvent> domainEvents = domainStream().query(EventQuery.matchAll()).map(Event::data).toList();
		assertEquals(domainBefore + 1, domainEvents.size());
		assertEquals(new FirstDomainEvent("hello"), domainEvents.get(domainEvents.size() - 1));
	}

	@Test
	void translateWithoutMatchingTranslatorThrows ( ) {
		// the registered translator only matches FirstDomainEvent, so the inbound event matches nothing
		Mock ctx = buildBoundedContext(baseBuilder().translator(new NonMatchingTranslator()));

		int domainBefore = domainStream().query(EventQuery.matchAll()).toList().size();

		NoTranslatorRegisteredException cause = assertTranslateThrows(() -> ctx.translate(new SomeInboundEvent("hello")));
		assertEquals(
			"no translator registered for inbound event 'SomeInboundEvent' in bounded context 'UnitTestBoundedContext'",
			cause.getMessage());

		// nothing was raised
		assertEquals(domainBefore, domainStream().query(EventQuery.matchAll()).toList().size());
	}

	@Test
	void translateWithNoTranslatorsRegisteredThrows ( ) {
		Mock ctx = buildBoundedContext(baseBuilder());

		assertTranslateThrows(() -> ctx.translate(new SomeInboundEvent("hello")));
	}

	/**
	 * The bounded context proxy propagates the real exception to the caller, so this helper asserts a
	 * NoTranslatorRegisteredException is thrown and returns it.
	 */
	private NoTranslatorRegisteredException assertTranslateThrows ( Runnable action ) {
		return assertThrows(NoTranslatorRegisteredException.class, action::run);
	}

	private EventStream<MockDomainEvent> domainStream ( ) {
		return EventStoreFactory.get().eventStore(eventStorage)
			.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"), MockDomainEvent.class);
	}

	private EventStream<MockInboundEvent> inboundStream ( ) {
		return EventStoreFactory.get().eventStore(eventStorage)
			.getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("unittests"));
	}

	static class SomeInboundTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			switch ( event ) {
				case SomeInboundEvent e -> context.event(new FirstDomainEvent(e.someValue()));
			}
		}
	}

	static class NonMatchingTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			// matches a domain event type that an inbound SomeInboundEvent can never satisfy
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// no-op for the test
		}
	}
}
