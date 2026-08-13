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
package org.sliceworkz.eventmodeling.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class TracingTest {

	private static final Instance INSTANCE = new Instance("logical", "physical", "process");

	@Test
	void initStartsWithNoCommand ( ) {
		Tracing tracing = Tracing.init(INSTANCE);
		assertNull(tracing.command());
	}

	@Test
	void commandBuilderSetsField ( ) {
		Tracing tracing = Tracing.init(INSTANCE).command("OpenAccountCommand");
		assertEquals("OpenAccountCommand", tracing.command());
	}

	@Test
	void commandPreservedAcrossOtherBuilders ( ) {
		Tracing tracing = Tracing.init(INSTANCE)
				.command("OpenAccountCommand")
				.actor("alice")
				.channel("api")
				.instance(new Instance("other", "p", "proc"));
		assertEquals("OpenAccountCommand", tracing.command());
		assertEquals("alice", tracing.actor());
		assertEquals("api", tracing.channel());
	}

	@Test
	void storeOnAddsXCommandTagWhenSet ( ) {
		Tracing tracing = Tracing.init(INSTANCE).command("OpenAccountCommand");
		EphemeralEvent<String> event = Event.of("hello", Tags.none());

		EphemeralEvent<String> stored = tracing.storeOn(event);

		assertTrue(stored.tags().tag("x-command").isPresent());
		assertEquals("OpenAccountCommand", stored.tags().tag("x-command").get().value());
	}

	@Test
	void storeOnDoesNotAddXCommandTagWhenAbsent ( ) {
		Tracing tracing = Tracing.init(INSTANCE);
		EphemeralEvent<String> event = Event.of("hello", Tags.none());

		EphemeralEvent<String> stored = tracing.storeOn(event);

		assertFalse(stored.tags().tag("x-command").isPresent());
	}

	@Test
	void storeOnDropsBlankValuesInsteadOfFailingTheEvent ( ) {
		Tracing tracing = Tracing.init(INSTANCE).command("   ").actor("");
		EphemeralEvent<String> event = Event.of("hello", Tags.none());

		EphemeralEvent<String> stored = tracing.storeOn(event);

		assertFalse(stored.tags().tag("x-command").isPresent());
		assertFalse(stored.tags().tag("x-actor").isPresent());
	}

	@Test
	void storeOnStripsSurroundingWhitespaceFromValues ( ) {
		Tracing tracing = Tracing.init(INSTANCE).channel(" web ");
		EphemeralEvent<String> event = Event.of("hello", Tags.none());

		EphemeralEvent<String> stored = tracing.storeOn(event);

		assertEquals("web", stored.tags().tag("x-channel").get().value());
	}

	@Test
	void storeOnPreservesExistingTags ( ) {
		Tracing tracing = Tracing.init(INSTANCE).command("OpenAccountCommand");
		EphemeralEvent<String> event = Event.of("hello", Tags.of(Tag.of("custom", "value")));

		EphemeralEvent<String> stored = tracing.storeOn(event);

		assertTrue(stored.tags().tag("custom").isPresent());
		assertEquals("value", stored.tags().tag("custom").get().value());
		assertEquals("OpenAccountCommand", stored.tags().tag("x-command").get().value());
	}

	@Test
	void everyFactoryMintsACorrelationId ( ) {
		assertTrue(Tracing.init(INSTANCE).correlationId() != null);
		assertTrue(Tracing.actorAndChannel("alice", "api").correlationId() != null);
		assertTrue(Tracing.automation(INSTANCE).correlationId() != null);
		assertTrue(Tracing.kernel(INSTANCE).correlationId() != null);
	}

	@Test
	void factoriesMintDistinctCorrelationIds ( ) {
		assertFalse(Tracing.init(INSTANCE).correlationId().equals(Tracing.init(INSTANCE).correlationId()));
	}

	@Test
	void correlationIdSurvivesEveryWither ( ) {
		Tracing tracing = Tracing.init(INSTANCE);
		String minted = tracing.correlationId();
		Tracing derived = tracing.command("OpenAccountCommand").actor("alice").channel("api")
				.agent("agent-1", "Agent One").instance(new Instance("other", "p", "proc"));
		assertEquals(minted, derived.correlationId());
	}

	@Test
	void correlationIdWitherReplacesTheMintedOne ( ) {
		Tracing tracing = Tracing.init(INSTANCE).correlationId("flow-42");
		assertEquals("flow-42", tracing.correlationId());
	}

	@Test
	void storeOnAddsCorrelationIdTag ( ) {
		Tracing tracing = Tracing.init(INSTANCE).correlationId("flow-42");
		EphemeralEvent<String> stored = tracing.storeOn(Event.of("hello", Tags.none()));

		assertEquals("flow-42", stored.tags().tag(Tracing.TAG_CORRELATION_ID).get().value());
	}

	@Test
	void storeOnLeavesNoCorrelationTagWhenTheIdIsNull ( ) {
		Tracing tracing = Tracing.init(INSTANCE).correlationId(null);
		EphemeralEvent<String> stored = tracing.storeOn(Event.of("hello", Tags.none()));

		assertFalse(stored.tags().tag(Tracing.TAG_CORRELATION_ID).isPresent());
	}

	@Test
	void readFromRoundTripsTheCorrelationId ( ) {
		Tracing tracing = Tracing.init(INSTANCE).correlationId("flow-42");
		EphemeralEvent<String> stored = tracing.storeOn(Event.of("hello", Tags.none()));
		Event<String> persisted = Event.of(
				EventStreamId.forContext("unittests"),
				EventReference.create(1, 1),
				stored.type(), stored.type(), stored.data(), stored.tags(),
				LocalDateTime.now());

		assertEquals("flow-42", Tracing.readFrom(persisted).correlationId());
	}

	@Test
	void readFromReportsNullForAnEventWrittenBeforeCorrelationExisted ( ) {
		Event<String> legacy = Event.of(
				EventStreamId.forContext("unittests"),
				EventReference.create(1, 1),
				EventType.ofType("String"), EventType.ofType("String"), "hello",
				Tags.of("x-actor", "alice"),
				LocalDateTime.now());

		assertNull(Tracing.readFrom(legacy).correlationId());
	}

	@Test
	void automationFactoryHasNoCommand ( ) {
		Tracing tracing = Tracing.automation(INSTANCE);
		assertNull(tracing.command());
		assertEquals(Tracing.AUTOMATION_ACTOR, tracing.actor());
	}

	@Test
	void kernelFactoryHasNoCommand ( ) {
		Tracing tracing = Tracing.kernel(INSTANCE);
		assertNull(tracing.command());
		assertEquals(Tracing.SYSTEM_ACTOR, tracing.actor());
	}

}
