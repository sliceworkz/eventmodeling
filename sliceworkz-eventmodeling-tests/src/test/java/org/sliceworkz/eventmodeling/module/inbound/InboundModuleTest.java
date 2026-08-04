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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

public class InboundModuleTest  extends AbstractMockDomainTest {
	
	private InvocationCountingEventStorage countingStorage;
	
	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		this.countingStorage = new InvocationCountingEventStorage(eventStorage());
		createBoundedContext();
	}

	@ForEachBackend
	void testInboundEventWithoutIdempotency ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(countingStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
		int eventsBefore = inboundEvents.query(EventQuery.matchAll()).toList().size();
		
		var inboundEvent = new SomeInboundEvent("test");
		
		boundedContext().incoming(inboundEvent);
		assertEquals(eventsBefore+1,inboundEvents.query(EventQuery.matchAll()).toList().size());
		
		boundedContext().incoming(inboundEvent);
		assertEquals(eventsBefore+2,inboundEvents.query(EventQuery.matchAll()).toList().size());
	}

	@ForEachBackend
	void testInboundEventWithIdempotency ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(countingStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
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
	
	@ForEachBackend
	void testInboundEventWithIdempotencyOnHash ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(countingStorage).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
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

	/**
	 * De-duplication reaches {@code incoming} as an empty result, never as an exception, so the
	 * {@code catch (OptimisticLockingException)} that used to sit here — annotated "idempotency check
	 * kicked in" — caught nothing on the path it was written for. What it did do was stand ready to
	 * swallow a genuine failure to append, losing the inbound event silently. There is no other record
	 * that it arrived, so the caller is the only party that can decide to retry, and it has to be told.
	 */
	@ForEachBackend
	void testAnInboundEventThatCannotBeAppendedIsReportedToTheCaller ( ) {
		EventStream<MockInboundEvent> inboundEvents = EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.anyContext().withPurpose("inbound"));
		int eventsBefore = inboundEvents.query(EventQuery.matchAll()).toList().size();

		countingStorage.failAppendsWith(new OptimisticLockingException(EventFilter.matchAll(), Optional.empty()));

		assertThrows(OptimisticLockingException.class,
				() -> boundedContext().incoming(new SomeInboundEvent("test"), Tag.of("uniqueKey", "123").toString()));

		// and it really was not stored, so treating this as "already known" would have lost it
		assertEquals(eventsBefore, inboundEvents.query(EventQuery.matchAll()).toList().size());
	}

	Mock createBoundedContext( ) {
		
		var builder = BoundedContext.newBuilder(Mock.class)
			.name("UnitTestBoundedContext")
			.eventStorage(countingStorage)
			.instance(InstanceFactory.determine("unittests"));

		return buildBoundedContext ( builder );
	}
}
