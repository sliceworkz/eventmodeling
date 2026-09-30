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

import org.sliceworkz.eventstore.events.Event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

public class DuplicateTranslatorNameTest extends AbstractMockDomainTest {

	@Test
	void duplicateTranslatorClassRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder()
				.translator(new MockTranslator())
				.translator(new MockTranslator())
				.build();
		});
		assertEquals("duplicate translator name 'MockTranslator' - bookmarks would collide", e.getMessage());
	}

	/**
	 * A translator's name keys the bookmark recording how far it has read the inbound stream, so a class
	 * that cannot supply a stable one would translate the whole inbound stream again on every start. It
	 * used to fail deeper down with a bare "id is required" naming neither the translator nor the reason.
	 */
	@Test
	void anonymousTranslatorRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder().translator(new MockTranslator() { }).build();
		});
		assertTrue(e.getMessage().contains("must be a named class"), e.getMessage());
		assertTrue(e.getMessage().contains("bookmark"), "the message should say why a name is needed: " + e.getMessage());
	}

	@Test
	void singleTranslatorBuildsSuccessfully ( ) {
		Mock ctx = buildBoundedContext(
			baseBuilder().translator(new MockTranslator())
		);
		assertNotNull(ctx);
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	static class MockTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void translate ( Event<MockInboundEvent> event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// no-op for the test
		}
	}
}
