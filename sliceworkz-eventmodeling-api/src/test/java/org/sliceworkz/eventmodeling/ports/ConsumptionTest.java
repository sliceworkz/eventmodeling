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
package org.sliceworkz.eventmodeling.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConsumptionTest {

	@Test
	void quantitiesAddUpAndKeepTheirOrder ( ) {
		Consumption sms = Consumption.of(" twilio ", "sms").quantity("messages", 1).quantity("segments", 2).quantity("segments", 1);
		assertEquals("twilio", sms.provider());
		assertEquals(Map.of("messages", 1L, "segments", 3L), sms.quantities());
		assertEquals(List.of("messages", "segments"), List.copyOf(sms.quantities().keySet()));
		assertEquals(0, sms.amount("characters"));
	}

	@Test
	void theBilledProductIsTheServedOneWhenItDiffers ( ) {
		assertEquals("big", Consumption.of("p", "big").billedProduct());
		assertEquals("small", Consumption.of("p", "big").served("small").billedProduct());
		assertNull(Consumption.of("p", "big").served("big").servedProduct(), "served as asked is no difference");
	}

	@Test
	void consumptionOfOneProductAddsUpWithoutWhatBelongsToOneCall ( ) {
		Consumption a = Consumption.of("p", "m").quantity("in", 1).reference("r1").attribute("region", "eu");
		Consumption b = Consumption.of("p", "m").quantity("in", 2).quantity("out", 5);
		Consumption sum = a.plus(b);
		assertEquals(Map.of("in", 3L, "out", 5L), sum.quantities());
		assertNull(sum.reference());
		assertTrue(sum.attributes().isEmpty());
		assertThrows(IllegalArgumentException.class, ( ) -> a.plus(Consumption.of("p", "other")));
	}

	@Test
	void whatMakesNoConsumptionIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, ( ) -> Consumption.of(" ", "m"));
		assertThrows(IllegalArgumentException.class, ( ) -> Consumption.of("p", null));
		assertThrows(IllegalArgumentException.class, ( ) -> Consumption.of("p", "m").quantity("in", -1));
		assertThrows(IllegalArgumentException.class, ( ) -> Consumption.of("p", "m").quantity("", 1));
	}

	@Test
	void theCanonicalConstructorIsLenientSinceEventsCarryIt ( ) {
		Consumption read = new Consumption("p", "m", null, null, null, null);
		assertTrue(read.quantities().isEmpty());
		assertTrue(read.attributes().isEmpty());
	}

	@Test
	void outsideAnOpenCallReportsGoNowhere ( ) {
		PortCall none = PortCall.current();
		assertFalse(none.monitored());
		assertSame(none, none.consumed(Consumption.of("p", "m").quantity("in", 1)));
	}

	@Test
	void anOpenCallRecordsUntilClosedAndRestoresTheOneBefore ( ) {
		List<Consumption> outer = new ArrayList<>();
		List<Consumption> inner = new ArrayList<>();
		try ( PortCall.Opened o = PortCall.open(outer::add) ) {
			try ( PortCall.Opened i = PortCall.open(inner::add) ) {
				PortCall.current().consumed(Consumption.of("p", "inner"));
			}
			PortCall.current().consumed(Consumption.of("p", "outer"));
			o.call().consumed(Consumption.of("p", "outer-again"));
		}
		assertEquals(List.of("inner"), inner.stream().map(Consumption::product).toList());
		assertEquals(List.of("outer", "outer-again"), outer.stream().map(Consumption::product).toList());
		assertFalse(PortCall.current().monitored());
	}

	@Test
	void aReportAfterTheCallClosedIsDropped ( ) {
		List<Consumption> seen = new ArrayList<>();
		PortCall call;
		try ( PortCall.Opened o = PortCall.open(seen::add) ) {
			call = o.call();
		}
		call.consumed(Consumption.of("p", "late"));
		assertTrue(seen.isEmpty());
	}
}
