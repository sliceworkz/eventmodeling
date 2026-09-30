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
package org.sliceworkz.eventmodeling.slices;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.MemberKind;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.SliceMember;

/**
 * Pins the derivation of a slice's type from what it registers: the Sliceworkz Modeler's rule over a
 * modeled slice's elements, with the registered components mapped onto those elements.
 */
class SliceTypeTest {

	@Test
	void nothingRegisteredIsUndefined ( ) {
		assertEquals(SliceType.UNDEFINED, SliceType.of(null));
		assertEquals(SliceType.UNDEFINED, SliceType.of(Set.of()));
	}

	@Test
	void aCommandIsAStateChange ( ) {
		assertEquals(SliceType.STATE_CHANGE, typeOf(MemberKind.COMMAND));
		assertEquals(SliceType.STATE_CHANGE, typeOf(MemberKind.COMMAND, MemberKind.COMMAND));
	}

	@Test
	void anAggregateIsAStateChange ( ) {
		assertEquals(SliceType.STATE_CHANGE, typeOf(MemberKind.AGGREGATE));
	}

	@Test
	void aCommandPublishingThroughADispatcherIsStillAStateChange ( ) {
		assertEquals(SliceType.STATE_CHANGE, typeOf(MemberKind.COMMAND, MemberKind.DISPATCHER));
	}

	@Test
	void aReadModelIsAStateRead ( ) {
		assertEquals(SliceType.STATE_READ, typeOf(MemberKind.READ_MODEL));
		assertEquals(SliceType.STATE_READ, typeOf(MemberKind.READ_MODEL, MemberKind.READ_MODEL));
	}

	@Test
	void anAutomationWithItsTodoListAndCommandIsAnAutomation ( ) {
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.READ_MODEL, MemberKind.AUTOMATION));
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.READ_MODEL, MemberKind.AUTOMATION, MemberKind.COMMAND));
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.READ_MODEL, MemberKind.AUTOMATION, MemberKind.COMMAND, MemberKind.DISPATCHER));
	}

	@Test
	void aPublicationIsAnAutomation ( ) {
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.PUBLISHER));
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.PUBLISHER, MemberKind.DISPATCHER));
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.READ_MODEL, MemberKind.PUBLISHER, MemberKind.DISPATCHER));
	}

	@Test
	void anAutomationCountsItsTodoListEvenWhenAnotherSliceRegistersIt ( ) {
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.AUTOMATION));
	}

	@Test
	void aCommandBesideAReadModelIsAnAutomationAsInTheModel ( ) {
		assertEquals(SliceType.AUTOMATION, typeOf(MemberKind.COMMAND, MemberKind.READ_MODEL));
	}

	@Test
	void aTranslatorIsATranslationWithOrWithoutItsCommand ( ) {
		assertEquals(SliceType.TRANSLATION, typeOf(MemberKind.TRANSLATOR));
		assertEquals(SliceType.TRANSLATION, typeOf(MemberKind.TRANSLATOR, MemberKind.COMMAND));
	}

	@Test
	void partsFittingNoPatternOrSeveralAreUnclear ( ) {
		assertEquals(SliceType.UNCLEAR, typeOf(MemberKind.DISPATCHER), "an outbound event and nothing to raise it");
		assertEquals(SliceType.UNCLEAR, typeOf(MemberKind.TRANSLATOR, MemberKind.READ_MODEL));
		assertEquals(SliceType.UNCLEAR, typeOf(MemberKind.TRANSLATOR, MemberKind.DISPATCHER));
		assertEquals(SliceType.UNCLEAR, typeOf(MemberKind.READ_MODEL, MemberKind.DISPATCHER));
	}

	@Test
	void aMemberOfAnUnknownKindCountsForNothing ( ) {
		assertEquals(SliceType.UNDEFINED, SliceType.of(List.of(new SliceMember("x", null, Aspect.COMMAND))));
	}

	@Test
	void theModelersRuleOverElementCounts ( ) {
		assertEquals(SliceType.UNDEFINED, SliceType.derive(0, 0, 0, 0, 0));
		assertEquals(SliceType.STATE_CHANGE, SliceType.derive(1, 0, 1, 0, 0));
		assertEquals(SliceType.STATE_CHANGE, SliceType.derive(1, 0, 2, 0, 1));
		assertEquals(SliceType.UNCLEAR, SliceType.derive(1, 0, 0, 0, 0), "a command raising nothing");
		assertEquals(SliceType.STATE_READ, SliceType.derive(0, 2, 0, 0, 0));
		assertEquals(SliceType.UNCLEAR, SliceType.derive(0, 1, 0, 0, 1), "a read model with an outbound event");
		assertEquals(SliceType.AUTOMATION, SliceType.derive(1, 1, 1, 0, 1));
		assertEquals(SliceType.TRANSLATION, SliceType.derive(1, 0, 1, 1, 0));
		assertEquals(SliceType.UNCLEAR, SliceType.derive(1, 0, 1, 1, 1), "a translation with an outbound event");
		assertEquals(SliceType.UNCLEAR, SliceType.derive(0, 0, 1, 0, 0), "an event nothing raises");
	}

	private static SliceType typeOf ( MemberKind... kinds ) {
		int[] n = { 0 };
		return SliceType.of(Arrays.stream(kinds).map(k -> new SliceMember("m" + n[0]++, k, Aspect.COMMAND)).toList());
	}

}
