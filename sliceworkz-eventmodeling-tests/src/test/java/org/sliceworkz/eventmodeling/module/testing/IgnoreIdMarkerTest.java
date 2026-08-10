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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.testing.CommandTest;

/**
 * {@code IGNORE_ID()} asserts, where it used to skip: the id field must be present and its value
 * must parse as a {@link UUID}. It used to share the {@code IGNORE_TEXT()} marker, so a test using
 * it on an event's id passed with the id being {@code "banana"}, {@code null} or missing — the
 * line was simply dropped from the comparison. {@code IGNORE_TEXT()} keeps exactly those
 * ignore-everything semantics and stays the escape hatch for ids that are legitimately not UUIDs.
 * <p>
 * Plain {@code @Test}s: this is behaviour of the published comparison helper, not of any storage
 * backend.
 */
public class IgnoreIdMarkerTest extends CommandTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	record Payload ( String id, String name ) { }

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@Test
	void aUuidValueSatisfiesIgnoreId ( ) {
		assertCompareObjects(
				new Payload(IGNORE_ID(), "alice"),
				new Payload(UUID.randomUUID().toString(), "alice"),
				"payload");
	}

	@Test
	void aNonUuidValueFailsIgnoreId ( ) {
		AssertionError failure = assertThrows(AssertionError.class, ( ) -> assertCompareObjects(
				new Payload(IGNORE_ID(), "alice"),
				new Payload("banana", "alice"),
				"payload"));

		assertTrue(failure.getMessage().contains("banana"), "failure should name the offending value: " + failure.getMessage());
		assertTrue(failure.getMessage().contains("id"), "failure should name the field: " + failure.getMessage());
	}

	@Test
	void aNullValueFailsIgnoreId ( ) {
		AssertionError failure = assertThrows(AssertionError.class, ( ) -> assertCompareObjects(
				new Payload(IGNORE_ID(), "alice"),
				new Payload(null, "alice"),
				"payload"));

		assertTrue(failure.getMessage().contains("id"), "failure should name the field: " + failure.getMessage());
	}

	@Test
	void theOtherFieldsAreStillCompared ( ) {
		assertThrows(AssertionError.class, ( ) -> assertCompareObjects(
				new Payload(IGNORE_ID(), "alice"),
				new Payload(UUID.randomUUID().toString(), "bob"),
				"payload"));
	}

	@Test
	void ignoreTextStillSkipsTheFieldEntirely ( ) {
		// the escape hatch for ids that are legitimately not UUIDs
		assertCompareObjects(
				new Payload(IGNORE_TEXT(), "alice"),
				new Payload("banana", "alice"),
				"payload");
	}

	// --- the downstream shape ------------------------------------------------------------------
	// Downstream projects do not put the marker on a bare String field: they wrap it in a value
	// object -- DomainConceptId.of(IGNORE_ID()) -- so the marker sits on the value object's nested
	// "value" line, often several times in one event. The actual ids come from
	// DomainConceptId.create(), which is UUID.randomUUID(). ConceptDefined below mirrors that
	// shape (e.g. the modeler's ProjectDefined / SliceDefined events).

	record ConceptDefined ( DomainConceptId id, DomainConceptId parentId, String name ) { }

	@Test
	void theDownstreamShapeAMarkerWrappedInAValueObjectPassesWithGeneratedIds ( ) {
		assertCompareObjects(
				new ConceptDefined(DomainConceptId.of(IGNORE_ID()), DomainConceptId.of(IGNORE_ID()), "My Project"),
				new ConceptDefined(DomainConceptId.create(), DomainConceptId.create(), "My Project"),
				"event");
	}

	@Test
	void theDownstreamShapeFailsWhenAWrappedIdIsNotAUuid ( ) {
		AssertionError failure = assertThrows(AssertionError.class, ( ) -> assertCompareObjects(
				new ConceptDefined(DomainConceptId.of(IGNORE_ID()), DomainConceptId.of(IGNORE_ID()), "My Project"),
				new ConceptDefined(DomainConceptId.create(), DomainConceptId.of("banana"), "My Project"),
				"event"));

		assertTrue(failure.getMessage().contains("banana"), "failure should name the offending value: " + failure.getMessage());
	}

	@Test
	void theDownstreamShapeStillComparesTheFieldsNextToTheWrappedIds ( ) {
		assertThrows(AssertionError.class, ( ) -> assertCompareObjects(
				new ConceptDefined(DomainConceptId.of(IGNORE_ID()), DomainConceptId.of(IGNORE_ID()), "My Project"),
				new ConceptDefined(DomainConceptId.create(), DomainConceptId.create(), "Another Project"),
				"event"));
	}

}
