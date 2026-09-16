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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.Untyped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The codec travels with the storage: a storage built with {@code .shredding(...)} carries it
 * ({@code EventStorage.shreddingCodec()}), and a context built over such a storage without a
 * {@code shredding(...)} of its own takes the storage's. What this pins down is that the builder does
 * not read its own null codec as "no codec" — which would make the very event types the storage was
 * configured for fail to register — and that a storage carrying none still fails such a context at
 * startup rather than storing personal data in the clear.
 * <p>
 * A plain {@code @Test}: this is builder behaviour, and the in-memory storage builder carries a codec
 * the way every other storage builder does.
 */
public class ShreddingCarriedByTheStorageTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");

	private final List<AutoCloseable> toClose = new ArrayList<>();

	@AfterEach
	void release ( ) throws Exception {
		for ( int i = toClose.size() - 1; i >= 0; i-- ) {
			toClose.get(i).close();
		}
	}

	@Test
	void aContextTakesTheCodecOfTheStorageItIsBuiltOver ( ) {
		EventStorage storage = InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).build();
		toClose.add(storage);

		// no .shredding(...) here: the storage carries it
		BoundedContext<ShreddingDomainEvent, MockInboundEvent, MockOutboundEvent> context = build(storage);
		context.start();

		context.event(new ShreddingDomainEvent.TransferMade("t-1", 5, "alice-42", "bob-77",
				Shreddable.of("Alice Martin", ALICE), Shreddable.of("Bob Jansen", DataSubject.of("customer", "bob-77"))),
				Tags.of("transfer", "t-1"));

		// a store built over the same storage, with nothing configured, reads under the same codec
		EventStore reader = EventStoreFactory.get().eventStore(storage);
		toClose.add(reader);
		ShreddingDomainEvent.TransferMade sealed = transfer(reader);
		assertEquals("Alice Martin", sealed.from().orElse(null));

		// and the context erases through the keys the storage holds
		assertEquals(1, context.erase(ALICE, ErasureReason.of("art.17")).keysShredded());
		ShreddingDomainEvent.TransferMade erased = transfer(reader);
		assertTrue(erased.from().isShredded());
		assertEquals("Bob Jansen", erased.to().orElse(null));
	}

	@Test
	void aStorageCarryingNoCodecStillFailsAContextWithPersonalDataAtStartup ( ) {
		EventStorage storage = InMemoryEventStorage.newBuilder().build();
		toClose.add(storage);

		// nothing on either side: a Shreddable component cannot be registered, so build() throws
		// before anything could be stored in the clear
		assertThrows(RuntimeException.class, ( ) -> build(storage));
	}

	@SuppressWarnings("unchecked")
	private BoundedContext<ShreddingDomainEvent, MockInboundEvent, MockOutboundEvent> build ( EventStorage storage ) {
		BoundedContextBuilder<?> builder = BoundedContext.newBuilder(Untyped.class)
				.eventTypes(ShreddingDomainEvent.class, MockInboundEvent.class, MockOutboundEvent.class)
				.name("ShreddingFromStorage")
				.instance(InstanceFactory.determine("unittests"))
				.eventStorage(storage);
		BoundedContext<ShreddingDomainEvent, MockInboundEvent, MockOutboundEvent> context =
				(BoundedContext<ShreddingDomainEvent, MockInboundEvent, MockOutboundEvent>) builder.build();
		toClose.add(context::terminate);
		return context;
	}

	private static ShreddingDomainEvent.TransferMade transfer ( EventStore reader ) {
		return (ShreddingDomainEvent.TransferMade) reader
				.getEventStream(EventStreamId.forContext("ShreddingFromStorage").withPurpose("domain"), ShreddingDomainEvent.class)
				.query(EventQuery.matchAll()).stream().findFirst().orElseThrow().data();
	}
}
