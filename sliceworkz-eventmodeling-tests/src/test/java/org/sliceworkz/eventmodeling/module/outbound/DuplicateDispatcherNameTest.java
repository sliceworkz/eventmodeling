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
package org.sliceworkz.eventmodeling.module.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * A dispatcher's name keys the bookmark recording what it has already published, so the two ways of
 * getting that name wrong both cost duplicate or missing publishing to an external system — the worst
 * outcome the framework has. Neither used to be checked here at all: only automations, translators and
 * read models were, and this registry was the one that mattered most.
 */
public class DuplicateDispatcherNameTest extends AbstractMockDomainTest {

	/**
	 * Two dispatchers sharing a name share one bookmark, so each advances it past events the other never
	 * saw and those events are simply never published. Nothing throws and nothing is logged, which is why
	 * this has to be refused at build time rather than left to a naming convention.
	 */
	@Test
	void duplicateDispatcherClassRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder()
				.dispatcher(new MockDispatcher())
				.dispatcher(new MockDispatcher())
				.build();
		});
		assertEquals("duplicate dispatcher name 'MockDispatcher' - bookmarks would collide", e.getMessage());
	}

	/**
	 * A class that cannot supply a stable simple name gets a fresh bookmark on every start, so the whole
	 * outbound stream would be dispatched again at every boot. It used to fail deeper down with a bare
	 * "id is required" naming neither the dispatcher nor the reason.
	 */
	@Test
	void anonymousDispatcherRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
			baseBuilder().dispatcher(new MockDispatcher() { }).build();
		});
		assertTrue(e.getMessage().contains("must be a named class"), e.getMessage());
		assertTrue(e.getMessage().contains("bookmark"), "the message should say why a name is needed: " + e.getMessage());
	}

	@Test
	void singleDispatcherBuildsSuccessfully ( ) {
		Mock ctx = buildBoundedContext(
			baseBuilder().dispatcher(new MockDispatcher())
		);
		assertNotNull(ctx);
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	static class MockDispatcher implements Dispatcher<MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			// no-op for the test
		}
	}
}
