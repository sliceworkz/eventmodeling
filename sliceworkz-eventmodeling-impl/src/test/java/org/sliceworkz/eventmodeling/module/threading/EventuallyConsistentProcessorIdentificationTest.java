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
package org.sliceworkz.eventmodeling.module.threading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification.Storage;

public class EventuallyConsistentProcessorIdentificationTest {

	Instance mockInstance = new Instance("someLogicalInstance", "somePhysicalInstance", "someProcessInstance");
	
	@Test
	void testParse ( ) {
		var actual = EventuallyConsistentProcessorIdentification.parse("auction/varia/system.err[ephemeral:logical#physical]");
		var expected = EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(new Instance("logical", "physical", "my-laptop-1198422-1757665356698-e018")).context("auction").type("varia").name("system.err").ephemeral().build();
		assertEquals(expected, actual);
		
		assertThrows(IllegalArgumentException.class, ()->EventuallyConsistentProcessorIdentification.parse(null));
		assertThrows(IllegalArgumentException.class, ()->EventuallyConsistentProcessorIdentification.parse(""));
	}
	
	@Test
	void testNoLocationOnSharedStorage ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->new EventuallyConsistentProcessorIdentification("context", "type", "id", Storage.SHARED, "location"));
		assertEquals("shared storage cannot have a location specifier", e.getMessage());
	
		// these should be OK
		// these should be OK
		var i = new EventuallyConsistentProcessorIdentification("context", "type", "id", Storage.SHARED, null);
		assertEquals("context/type/id[shared]", i.toString());

		var j = new EventuallyConsistentProcessorIdentification("context", "type", "id", Storage.EPHEMERAL, "location");
		assertEquals("context/type/id[ephemeral:location]", j.toString());
		
		var k = new EventuallyConsistentProcessorIdentification("context", "type", "id", Storage.LOCAL, "location");
		assertEquals("context/type/id[local:location]", k.toString());

	}
	
	@Test
	void testBuilderSharedStorage ( ) {
		EventuallyConsistentProcessorIdentificationBuilder builder = EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(mockInstance);
		EventuallyConsistentProcessorIdentification epi = builder.automation().context("someContext").name(EventuallyConsistentProcessorIdentificationTest.class).shared().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("automation", epi.type());
		assertEquals("EventuallyConsistentProcessorIdentificationTest", epi.id());
		assertEquals(Storage.SHARED, epi.storage());
		assertEquals("someContext/automation/EventuallyConsistentProcessorIdentificationTest[shared]", epi.toString());
	}
	
	@Test
	void testBuilderLocalStorage ( ) {
		EventuallyConsistentProcessorIdentificationBuilder builder = EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(mockInstance);
		EventuallyConsistentProcessorIdentification epi = builder.readmodel().context("someContext").name(EventuallyConsistentProcessorIdentificationTest.class).local().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("readmodel", epi.type());
		assertEquals("EventuallyConsistentProcessorIdentificationTest", epi.id());
		assertEquals(Storage.LOCAL, epi.storage());
		assertEquals("someContext/readmodel/EventuallyConsistentProcessorIdentificationTest[local:someLogicalInstance#somePhysicalInstance]", epi.toString());
		assertTrue(epi.toString().endsWith("]"));
	}
	
	@Test
	void testBuilderEphemeralStorage ( ) {
		EventuallyConsistentProcessorIdentificationBuilder builder = EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(mockInstance);
		EventuallyConsistentProcessorIdentification epi = builder.translator().context("someContext").name(EventuallyConsistentProcessorIdentificationTest.class).ephemeral().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("translator", epi.type());
		assertEquals("EventuallyConsistentProcessorIdentificationTest", epi.id());
		assertEquals(Storage.EPHEMERAL, epi.storage());
		assertEquals("someContext/translator/EventuallyConsistentProcessorIdentificationTest[ephemeral:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}
	
	@Test
	void testBuilderWithObjectParameter ( ) {
		EventuallyConsistentProcessorIdentificationBuilder builder = EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(mockInstance);
		EventuallyConsistentProcessorIdentification epi = builder.translator().context("someContext").name(new EventuallyConsistentProcessorIdentificationTest()).ephemeral().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("translator", epi.type());
		assertEquals("EventuallyConsistentProcessorIdentificationTest", epi.id());
		assertEquals(Storage.EPHEMERAL, epi.storage());
		assertEquals("someContext/translator/EventuallyConsistentProcessorIdentificationTest[ephemeral:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}
	
	@Test
	void testCase ( ) {
		EventuallyConsistentProcessorIdentification.EventuallyConsistentProcessorIdentificationBuilder.newBuilder(mockInstance).context("test").readmodel().name("testReadModel").local().build().toString();
	}

}
