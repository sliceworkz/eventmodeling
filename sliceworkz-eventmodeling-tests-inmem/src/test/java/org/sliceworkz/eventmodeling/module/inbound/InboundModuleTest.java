/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockBoundedContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class InboundModuleTest  extends AbstractMockDomainTest {
	
	private EventStorage rawEventStorage;
	private InvocationCountingEventStorage eventStorage;
	
	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.rawEventStorage = createEventStorage();
		this.eventStorage = new InvocationCountingEventStorage(rawEventStorage);
		createBoundedContext();
	}
	
	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(rawEventStorage);
		boundedContext().stop();
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testInboundEventWithoutIdempotency ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
		int eventsBefore = inboundEvents.query(EventQuery.matchAll()).toList().size();
		
		var inboundEvent = new SomeInboundEvent("test");
		
		boundedContext().incoming(inboundEvent);
		assertEquals(eventsBefore+1,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		boundedContext().incoming(inboundEvent);
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());
	}

	@Test
	void testInboundEventWithIdempotency ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
		int eventsBefore = inboundEvents.query(EventQuery.matchAll()).toList().size();
		
		var inboundEvent = new SomeInboundEvent("test");
		
		boundedContext().incoming(inboundEvent, Tag.of("uniqueKey", "123").toString());
		assertEquals(eventsBefore+1,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		boundedContext().incoming(inboundEvent, Tag.of("uniqueKey", "456").toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());

		// only duplicates from here, idempotency check should be applied and the events should be ignored
		
		// a duplicate key leads to silent ignore because of idempotency
		boundedContext().incoming(inboundEvent, Tag.of("uniqueKey", "123").toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		// a duplicate key leads to silent ignore because of idempotency
		boundedContext().incoming(inboundEvent, Tag.of("uniqueKey", "456").toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());

	}
	
	@Test
	void testInboundEventWithIdempotencyOnHash ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
		int eventsBefore = inboundEvents.query(EventQuery.matchAll()).toList().size();
		
		var e1 = new SomeInboundEvent("test");
		var e2 = new SomeInboundEvent("test2");
		var e3 = new SomeInboundEvent("test");
		var e4 = new SomeInboundEvent("test2");
		
		assertFalse(e1 == e3); // while they're different objects ...
		assertEquals(e1.hashCode(), e3.hashCode()); // ... their hashcodes are equal if their content is equal
		
		assertFalse(e2 == e4);
		assertEquals(e2.hashCode(), e4.hashCode());

		boundedContext().incoming(e1, Tag.of("hash", String.valueOf(e1.hashCode())).toString());
		assertEquals(eventsBefore+1,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		boundedContext().incoming(e2, Tag.of("hash", String.valueOf(e2.hashCode())).toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());

		// only duplicates from here, idempotency check should be applied and the events should be ignored
		
		// a duplicate key leads to silent ignore because of idempotency
		boundedContext().incoming(e3, Tag.of("hash", String.valueOf(e3.hashCode())).toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		// a duplicate key leads to silent ignore because of idempotency
		boundedContext().incoming(e4, Tag.of("hash", String.valueOf(e4.hashCode())).toString());
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());

	}

	
	MockBoundedContext createBoundedContext( ) {
		
		var builder = BoundedContext.newBuilder(MockDomainEvent.class, MockInboundEvent.class, MockOutboundEvent.class)
			.name("UnitTestBoundedContext")
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("unittests"));

		return buildBoundedContext ( builder );
	}
}
