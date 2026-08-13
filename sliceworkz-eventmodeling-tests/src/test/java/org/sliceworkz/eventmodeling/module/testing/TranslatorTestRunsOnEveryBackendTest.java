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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.UnclaimedInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.TranslatorTest;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * That a test written against the <em>published</em> {@link TranslatorTest} runs against every
 * registered event storage, not only the in-memory one — the {@code CommandTestRunsOnEveryBackendTest}
 * guarantee, for the translator base. The storage-sensitive half is that the raised domain events are
 * durably appended to the backend's own storage while the inbound event is not persisted anywhere;
 * the exception paths are framework behaviour and run once, in memory.
 */
public class TranslatorTestRunsOnEveryBackendTest extends TranslatorTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	/**
	 * Translates a {@code SomeInboundEvent} by value: {@code ignore} raises nothing, {@code boom}
	 * fails, anything else becomes a {@code FirstDomainEvent}.
	 */
	static class ScriptedTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			if ( event instanceof SomeInboundEvent some ) {
				switch ( some.someValue() ) {
					case "ignore" -> { }
					case "boom" -> throw new IllegalStateException("translator failure on boom");
					default -> context.event(new FirstDomainEvent(some.someValue()));
				}
			}
		}
	}

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@Override
	public List<Translator<MockInboundEvent,MockDomainEvent>> translators ( ) {
		return List.of(new ScriptedTranslator());
	}

	@ForEachBackend
	void aTranslationRaisesItsDomainEventOnEveryBackend ( ) {
		given()
			.when(new SomeInboundEvent("hello"))
			.then()
			.event(new FirstDomainEvent("hello"));

		// the raised event went through the backend's own storage
		assertEquals(1, domainStream().query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void priorDomainHistoryIsNotMistakenForRaisedEvents ( ) {
		given(new SecondDomainEvent("prior"))
			.when(new SomeInboundEvent("hello"))
			.then()
			.event(new FirstDomainEvent("hello"));
	}

	@Test
	void aTranslatorMayRaiseNothing ( ) {
		given()
			.when(new SomeInboundEvent("ignore"))
			.then()
			.noEvents();
	}

	@Test
	void anInboundEventNoTranslatorClaimsIsLoud ( ) {
		given()
			.when(new UnclaimedInboundEvent("hello"))
			.then()
			.noTranslatorRegistered();
	}

	@Test
	void aThrowingTranslatorIsReportedWithItsRootCause ( ) {
		given()
			.when(new SomeInboundEvent("boom"))
			.then()
			.error("translator failure on boom");
	}

	@Test
	void aPlainTestStillRunsAgainstTheInMemoryStore ( ) {
		assertNotNull(eventStorage());
		given()
			.when(new SomeInboundEvent("in-memory"))
			.then()
			.event(new FirstDomainEvent("in-memory"));
	}

}
