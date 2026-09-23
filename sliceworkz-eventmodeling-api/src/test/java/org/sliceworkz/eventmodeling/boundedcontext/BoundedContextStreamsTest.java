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
package org.sliceworkz.eventmodeling.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins the stream layout as the wire format it is. Every stored event of every deployed context sits
 * under one of these ids, so this is the one place the literals are written out in a test — the
 * rest of the suite derives them from {@link BoundedContextStreams}, and a change that makes this
 * test fail is a change to where events are stored, which no deployment's history follows.
 */
public class BoundedContextStreamsTest {

	@Test
	void aContextKeepsOneStreamPerKindOfEventUnderItsOwnName ( ) {
		assertEquals(EventStreamId.forContext("banking").withPurpose("domain"), BoundedContextStreams.domain("banking"));
		assertEquals(EventStreamId.forContext("banking").withPurpose("inbound"), BoundedContextStreams.inbound("banking"));
		assertEquals(EventStreamId.forContext("banking").withPurpose("outbound"), BoundedContextStreams.outbound("banking"));
	}

	@Test
	void thePurposesAreTheOnesTheIdsCarry ( ) {
		assertEquals("domain", BoundedContextStreams.DOMAIN);
		assertEquals("inbound", BoundedContextStreams.INBOUND);
		assertEquals("outbound", BoundedContextStreams.OUTBOUND);
	}
}
