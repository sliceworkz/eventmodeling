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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;

/**
 * Tests for command-level idempotency key support.
 *
 * Two mechanisms are tested:
 * <ul>
 *   <li><b>External key:</b> The caller provides an idempotency key via
 *       {@code execute(command, idempotencyKey)}.</li>
 *   <li><b>Internal key:</b> The command itself declares an idempotency key
 *       strategy via {@code CommandResult.idempotencyKey()},
 *       {@code fallbackIdempotencyKey()}, {@code requireIdempotencyKey()},
 *       {@code exclusiveIdempotencyKey()}, or {@code forbidIdempotencyKey()}.</li>
 * </ul>
 *
 * The framework resolves internal vs external keys based on the strategy chosen
 * by the command, then applies the resolved key to the raised event(s).
 */
public class DCBCommandIdempotencyTest extends AbstractMockDomainTest {

	private EventStorage eventStorage;
	private EventStream<MockDomainEvent> directStream;

	@BeforeEach
	protected void setUp() {
		super.setUp();
		this.eventStorage = InMemoryEventStorage.newBuilder().build();
	}

	@AfterEach
	protected void tearDown() {
		if (boundedContext() != null) {
			boundedContext().stop();
		}
	}

	private Mock buildDomain() {
		var builder =
				BoundedContext.newBuilder(Mock.class)
						.name("UnitTestBoundedContext")
						.eventStorage(eventStorage)
						.instance(InstanceFactory.determine("unittests"));

		Mock domain = buildBoundedContext(builder);

		directStream = EventStoreFactory.get().eventStore(eventStorage)
				.getEventStream(
						EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"),
						MockDomainEvent.class);

		return domain;
	}

	private long countDomainEvents() {
		return directStream.query(EventQuery.matchAll()).toList().size();
	}

	// ════════════════════════════════════════════════════════════════════
	// COMMANDS
	// ════════════════════════════════════════════════════════════════════

	static class SingleEventCommand implements Command<MockDomainEvent> {

		private final String value;

		SingleEventCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	static class MultiEventCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("first"), Tags.none());
			result.raiseEvent(new FirstDomainEvent("second"), Tags.none());
		}
	}

	static class NoEventCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
		}
	}

	/** Command that uses {@code fallbackIdempotencyKey()} — internal fallback, external overrides. */
	static class FallbackKeyCommand implements Command<MockDomainEvent> {

		private final String value;
		private final String key;

		FallbackKeyCommand(String value, String key) {
			this.value = value;
			this.key = key;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.fallbackIdempotencyKey(key)
					.raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	/** Command that uses {@code requireIdempotencyKey()} — requires external key. */
	static class RequireExternalKeyCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.requireIdempotencyKey()
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code exclusiveIdempotencyKey()} — rejects external key. */
	static class ExclusiveKeyCommand implements Command<MockDomainEvent> {

		private final String key;

		ExclusiveKeyCommand(String key) {
			this.key = key;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.exclusiveIdempotencyKey(key)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code idempotencyKey()} — ignores external key. */
	static class IdempotencyKeyCommand implements Command<MockDomainEvent> {

		private final String key;

		IdempotencyKeyCommand(String key) {
			this.key = key;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.idempotencyKey(key)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code forbidIdempotencyKey()} — rejects any external key. */
	static class ForbidExternalKeyCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.forbidIdempotencyKey()
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: External idempotency key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void externalKey_firstExecution_storesEvent() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new SingleEventCommand("test"), "key-1");

		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void externalKey_duplicateExecution_silentlyIgnored() {
		Mock domain = buildDomain();

		Optional<EventReference> first = domain.execute(new SingleEventCommand("test"), "key-1");
		assertTrue(first.isPresent());

		Optional<EventReference> second = domain.execute(new SingleEventCommand("test"), "key-1");
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void externalKey_differentKeys_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new SingleEventCommand("test-1"), "key-1");
		domain.execute(new SingleEventCommand("test-2"), "key-2");

		assertEquals(2, countDomainEvents());
	}

	@Test
	void noKey_duplicateCommands_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new SingleEventCommand("test"));
		domain.execute(new SingleEventCommand("test"));

		assertEquals(2, countDomainEvents());
	}

	@Test
	void externalKey_noEventsRaised_returnsEmpty() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new NoEventCommand(), "key-1");

		assertTrue(result.isEmpty());
		assertEquals(0, countDomainEvents());
	}

	@Test
	void externalKey_multipleEvents_throwsException() {
		Mock domain = buildDomain();

		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class,
				() -> domain.execute(new MultiEventCommand(), "key-1"));

		Throwable cause = e.getCause().getCause();
		assertTrue(cause instanceof IllegalArgumentException,
				"Expected IllegalArgumentException but got: " + cause.getClass().getName());
	}

	@Test
	void externalKey_eventAlreadyHasKey_preservesEventKey() {
		Mock domain = buildDomain();

		Command<MockDomainEvent> cmd = new Command<>() {
			@Override
			public void execute(
					CommandContext<MockDomainEvent, MockDomainEvent> context) {
				var result = context.noDecisionModels();
				result.raiseEvent(new FirstDomainEvent("test"), Tags.none(), "event-level-key");
			}
		};

		Optional<EventReference> first = domain.execute(cmd, "command-level-key");
		assertTrue(first.isPresent());

		// event-level key preserved; re-executing deduplicates based on that key
		Optional<EventReference> second = domain.execute(cmd, "command-level-key");
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: fallbackIdempotencyKey() — internal fallback, external overrides
	// ════════════════════════════════════════════════════════════════════

	@Test
	void fallbackKey_noExternalKey_usesInternalKey() {
		Mock domain = buildDomain();

		domain.execute(new FallbackKeyCommand("test", "internal-key"));

		// duplicate with same internal key is deduplicated
		Optional<EventReference> second = domain.execute(new FallbackKeyCommand("test", "internal-key"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void fallbackKey_withExternalKey_externalOverrides() {
		Mock domain = buildDomain();

		// first execution uses external key
		domain.execute(new FallbackKeyCommand("test", "internal-key"), "external-key");

		// second execution with same internal key but no external — not deduplicated
		// because the first event was stored with the external key
		Optional<EventReference> second = domain.execute(new FallbackKeyCommand("test", "internal-key"));
		assertTrue(second.isPresent());

		assertEquals(2, countDomainEvents());
	}

	@Test
	void fallbackKey_withExternalKey_duplicateExternal_deduplicated() {
		Mock domain = buildDomain();

		domain.execute(new FallbackKeyCommand("test", "internal-key"), "external-key");

		// same external key deduplicates regardless of internal key
		Optional<EventReference> second = domain.execute(new FallbackKeyCommand("test", "different-internal"), "external-key");
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: requireIdempotencyKey() — requires external key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void requireKey_withExternalKey_succeeds() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new RequireExternalKeyCommand(), "external-key");
		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void requireKey_withoutExternalKey_throwsException() {
		Mock domain = buildDomain();

		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class,
				() -> domain.execute(new RequireExternalKeyCommand()));

		Throwable cause = e.getCause().getCause();
		assertTrue(cause instanceof IllegalStateException,
				"Expected IllegalStateException but got: " + cause.getClass().getName());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: exclusiveIdempotencyKey() — rejects external key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void exclusiveKey_noExternalKey_usesInternalKey() {
		Mock domain = buildDomain();

		domain.execute(new ExclusiveKeyCommand("my-key"));

		Optional<EventReference> second = domain.execute(new ExclusiveKeyCommand("my-key"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void exclusiveKey_withExternalKey_throwsException() {
		Mock domain = buildDomain();

		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class,
				() -> domain.execute(new ExclusiveKeyCommand("my-key"), "external-key"));

		Throwable cause = e.getCause().getCause();
		assertTrue(cause instanceof IllegalStateException,
				"Expected IllegalStateException but got: " + cause.getClass().getName());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey() — ignores external key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void idempotencyKey_noExternalKey_usesInternalKey() {
		Mock domain = buildDomain();

		domain.execute(new IdempotencyKeyCommand("my-key"));

		Optional<EventReference> second = domain.execute(new IdempotencyKeyCommand("my-key"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void idempotencyKey_withExternalKey_internalWins() {
		Mock domain = buildDomain();

		// internal key "my-key" is used, external key ignored
		domain.execute(new IdempotencyKeyCommand("my-key"), "external-key");

		// same internal key, different external — still deduplicated by internal
		Optional<EventReference> second = domain.execute(new IdempotencyKeyCommand("my-key"), "different-external");
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void idempotencyKey_differentInternalKeys_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new IdempotencyKeyCommand("key-a"), "same-external");
		domain.execute(new IdempotencyKeyCommand("key-b"), "same-external");

		// different internal keys means different events stored, despite same external
		assertEquals(2, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: forbidIdempotencyKey() — rejects external key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void forbidKey_noExternalKey_succeeds() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new ForbidExternalKeyCommand());
		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void forbidKey_withExternalKey_throwsException() {
		Mock domain = buildDomain();

		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class,
				() -> domain.execute(new ForbidExternalKeyCommand(), "external-key"));

		Throwable cause = e.getCause().getCause();
		assertTrue(cause instanceof IllegalStateException,
				"Expected IllegalStateException but got: " + cause.getClass().getName());
	}

}
