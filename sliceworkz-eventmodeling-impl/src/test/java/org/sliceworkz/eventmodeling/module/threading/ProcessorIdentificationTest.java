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
package org.sliceworkz.eventmodeling.module.threading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.ProcessorIdentificationBuilder;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;

public class ProcessorIdentificationTest {

	Instance mockInstance = new Instance("someLogicalInstance", "somePhysicalInstance", "someProcessInstance");

	@Test
	void testParse ( ) {
		var actual = ProcessorIdentification.parse("auction/varia/system.err[ephemeral:logical#physical]");
		var expected = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(new Instance("logical", "physical", "my-laptop-1198422-1757665356698-e018")).context("auction").type("varia").name("system.err").ephemeral().build();
		assertEquals(expected, actual);

		assertThrows(IllegalArgumentException.class, ()->ProcessorIdentification.parse(null));
		assertThrows(IllegalArgumentException.class, ()->ProcessorIdentification.parse(""));
	}

	@Test
	void testNoLocationOnSharedStorage ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->new ProcessorIdentification("context", "type", "id", Storage.SHARED, "location"));
		assertEquals("shared storage cannot have a location specifier", e.getMessage());

		// these should be OK
		// these should be OK
		var i = new ProcessorIdentification("context", "type", "id", Storage.SHARED, null);
		assertEquals("context/type/id[shared]", i.toString());

		var j = new ProcessorIdentification("context", "type", "id", Storage.EPHEMERAL, "location");
		assertEquals("context/type/id[ephemeral:location]", j.toString());

		var k = new ProcessorIdentification("context", "type", "id", Storage.LOCAL, "location");
		assertEquals("context/type/id[local:location]", k.toString());

	}

	@Test
	void testBuilderSharedStorage ( ) {
		ProcessorIdentificationBuilder builder = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance);
		ProcessorIdentification epi = builder.automation().context("someContext").name(ProcessorIdentificationTest.class).shared().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("automation", epi.type());
		assertEquals("ProcessorIdentificationTest", epi.id());
		assertEquals(Storage.SHARED, epi.storage());
		assertEquals("someContext/automation/ProcessorIdentificationTest[shared]", epi.toString());
	}

	@Test
	void testBuilderLocalStorage ( ) {
		ProcessorIdentificationBuilder builder = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance);
		ProcessorIdentification epi = builder.readmodel().context("someContext").name(ProcessorIdentificationTest.class).local().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("readmodel", epi.type());
		assertEquals("ProcessorIdentificationTest", epi.id());
		assertEquals(Storage.LOCAL, epi.storage());
		assertEquals("someContext/readmodel/ProcessorIdentificationTest[local:someLogicalInstance#somePhysicalInstance]", epi.toString());
		assertTrue(epi.toString().endsWith("]"));
	}

	@Test
	void testBuilderEphemeralStorage ( ) {
		ProcessorIdentificationBuilder builder = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance);
		ProcessorIdentification epi = builder.translator().context("someContext").name(ProcessorIdentificationTest.class).ephemeral().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("translator", epi.type());
		assertEquals("ProcessorIdentificationTest", epi.id());
		assertEquals(Storage.EPHEMERAL, epi.storage());
		assertEquals("someContext/translator/ProcessorIdentificationTest[ephemeral:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}

	@Test
	void testBuilderWithObjectParameter ( ) {
		ProcessorIdentificationBuilder builder = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance);
		ProcessorIdentification epi = builder.translator().context("someContext").name(new ProcessorIdentificationTest()).ephemeral().build();
		assertNotNull(epi);
		assertEquals("someContext", epi.context());
		assertEquals("translator", epi.type());
		assertEquals("ProcessorIdentificationTest", epi.id());
		assertEquals(Storage.EPHEMERAL, epi.storage());
		assertEquals("someContext/translator/ProcessorIdentificationTest[ephemeral:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}

	@Test
	void testCase ( ) {
		ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance).context("test").readmodel().name("testReadModel").local().build().toString();
	}

	@Test
	void testEphemeralReadModelStorageScopesToTheInstance ( ) {
		ProcessorIdentification epi = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance)
				.context("ctx").readmodel().name("TodoList")
				.storage(ReadModelStorage.EPHEMERAL)
				.build();
		assertEquals(Storage.EPHEMERAL, epi.storage());
		assertEquals("ctx/readmodel/TodoList[ephemeral:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}

	@Test
	void testLocalReadModelStorageScopesToTheInstance ( ) {
		ProcessorIdentification epi = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance)
				.context("ctx").readmodel().name("TodoList")
				.storage(ReadModelStorage.LOCAL)
				.build();
		assertEquals(Storage.LOCAL, epi.storage());
		assertEquals("ctx/readmodel/TodoList[local:someLogicalInstance#somePhysicalInstance]", epi.toString());
	}

	@Test
	void testSharedReadModelStorageHasNoLocation ( ) {
		ProcessorIdentification epi = ProcessorIdentification.ProcessorIdentificationBuilder.newBuilder(mockInstance)
				.context("ctx").readmodel().name("TodoList")
				.storage(ReadModelStorage.SHARED)
				.build();
		assertEquals(Storage.SHARED, epi.storage());
		assertEquals("ctx/readmodel/TodoList[shared]", epi.toString());
	}

}
