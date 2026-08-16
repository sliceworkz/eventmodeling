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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.AbstractBoundedContextTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.stream.AppendCriteria;

/**
 * A bounded context protects personal data and can erase it.
 * <p>
 * The framework itself has nothing to do with shredding — a {@code Shreddable} is an ordinary value in
 * a payload as far as commands, projectors and dispatchers are concerned. What this pins down is the
 * wiring: that {@code BoundedContextBuilder.shredding(...)} reaches the store the context builds, and
 * that {@code erase(...)} on the context reaches the keys.
 */
public class BoundedContextShreddingTest extends AbstractBoundedContextTest<ShreddingDomainEvent, MockInboundEvent, MockOutboundEvent> {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final DataSubject BOB = DataSubject.of("customer", "bob-77");

	@Override
	public Class<ShreddingDomainEvent> domainEventType ( ) {
		return ShreddingDomainEvent.class;
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
	public void configure ( BoundedContextBuilder<?> builder ) {
		// nothing: the harness already wires an in-memory key store, which is the point of this test
	}

	@Test
	void aContextProtectsPersonalDataAndErasesOneSubjectAtATime ( ) {
		domainStream().append(AppendCriteria.none(), Event.of(
				new ShreddingDomainEvent.TransferMade("t-9001", 25000, "alice-42", "bob-77",
						Shreddable.of("Alice Martin", ALICE),
						Shreddable.of("Bob Jansen", BOB)),
				Tags.of("transfer", "t-9001")));

		ShreddingDomainEvent.TransferMade before =
				(ShreddingDomainEvent.TransferMade) domainStream().query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertEquals("Alice Martin", before.from().orElse(null));
		assertEquals("Bob Jansen", before.to().orElse(null));

		ErasureReport report = kernel().erase(ALICE, ErasureReason.of("GDPR art.17 request #4711"));
		assertEquals(1, report.keysShredded());
		assertFalse(report.isNoop());

		ShreddingDomainEvent.TransferMade after =
				(ShreddingDomainEvent.TransferMade) domainStream().query(EventQuery.matchAll()).findFirst().orElseThrow().data();

		assertTrue(after.from().isShredded(), "the erased subject's data is still readable");
		assertEquals("Bob Jansen", after.to().orElse(null), "erasing one subject took the other's data with it");

		// the event itself is untouched, so anything not personal still reconciles
		assertEquals("t-9001", after.transferId());
		assertEquals(25000, after.cents());
		assertEquals("alice-42", after.fromCustomerId());
	}

	@Test
	void erasingASubjectThatHoldsNoKeysIsANoop ( ) {
		ErasureReport report = kernel().erase(
				DataSubject.of("customer", "nobody"), ErasureReason.of("art.17"));

		assertTrue(report.isNoop());
		assertEquals(0, report.keysShredded());
	}

}
