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
package org.sliceworkz.eventmodeling.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Tag;

public class DomainConceptTagTest {
	
	@Test
	void testParse ( ) {
		String s = "customer:123";
		Tag expected = DomainConceptTag.of(DomainConcept.of("customer"), DomainConceptId.of("123"));
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(expected.key(), t.key());
		assertEquals(expected.value(), t.value());
		assertEquals(expected, t);
		assertEquals(s, t.toString());
	}

	@Test
	void testParseWithSpaces ( ) {
		String s = "  customer  :   123    ";
		String e = "customer:123";
		Tag expected = DomainConceptTag.of(DomainConcept.of("customer"), DomainConceptId.of("123"));
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(expected.key(), t.key());
		assertEquals(expected.value(), t.value());
		assertEquals(expected, t);
		assertEquals(e, t.toString());
	}

	@Test
	void testParseWithoutConcept ( ) {
		String s = ":123";
		Tag expected = DomainConceptTag.of(null, DomainConceptId.of("123"));
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(expected.key(), t.key());
		assertEquals(expected.value(), t.value());
		assertEquals(expected, t);
		assertEquals(s, t.toString());
	}

	@Test
	void testParseWithoutId ( ) {
		String s = "customer";
		Tag expected = DomainConceptTag.of(DomainConcept.of("customer"));
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(expected.key(), t.key());
		assertEquals(expected.value(), t.value());
		assertEquals(expected, t);
		assertEquals(s, t.toString());
	}
	
}
