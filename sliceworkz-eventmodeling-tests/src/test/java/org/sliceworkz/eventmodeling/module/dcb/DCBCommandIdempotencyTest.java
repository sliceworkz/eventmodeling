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

import org.sliceworkz.eventmodeling.commands.Execution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult.CallerKey;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;

/**
 * Tests for command-level idempotency key support.
 *
 * Two mechanisms are tested:
 * <ul>
 *   <li><b>External key:</b> The caller provides an idempotency key via
 *       {@code execute(command, idempotencyKey)}.</li>
 *   <li><b>Internal key:</b> The command itself declares an idempotency key
 *       strategy via {@code CommandResult.idempotencyKey(key, CallerKey)} or
 *       {@code CommandResult.idempotencyKey(CallerKey)}, one test group per choice below.</li>
 * </ul>
 *
 * The framework resolves internal vs external keys based on the strategy chosen
 * by the command, then applies the resolved key to the raised event(s).
 */
public class DCBCommandIdempotencyTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> directStream;

	private Mock buildDomain() {
		var builder =
				BoundedContext.newBuilder(Mock.class)
						.name("UnitTestBoundedContext")
						.eventStorage(eventStorage())
						.instance(InstanceFactory.determine("unittests"));

		Mock domain = buildBoundedContext(builder);

		directStream = EventStore.on(eventStorage()).build()
				.getEventStream(
						BoundedContextStreams.domain("UnitTestBoundedContext"),
						MockDomainEvent.class);

		return domain;
	}

	private long countDomainEvents() {
		return directStream.query(EventFilter.matchAll()).size();
	}

	/** The keys as stored, in stream order: the public {@code Event} does not carry them. */
	private List<String> storedKeys() {
		return eventStorage().query(EventFilter.matchAll(),
				BoundedContextStreams.domain("UnitTestBoundedContext"), null, Limit.none()).stream()
				.map(StoredEvent::idempotencyKey).toList();
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

		private final int count;

		MultiEventCommand() {
			this(2);
		}

		MultiEventCommand(int count) {
			this.count = count;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			for ( int i = 1; i <= count; i++ ) {
				result.raiseEvent(new FirstDomainEvent("event-" + i), Tags.none());
			}
		}
	}

	/** Raises two events, keying the first itself and leaving the second to the command-level key. */
	static class PartlyKeyedMultiEventCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("own"), Tags.none(), "own-key");
			result.raiseEvent(new FirstDomainEvent("derived"), Tags.none());
		}
	}

	static class NoEventCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
		}
	}

	/** Command that uses {@code idempotencyKey(key, CallerKey.PREFERRED)} — internal fallback, external overrides. */
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
					.idempotencyKey(key, CallerKey.PREFERRED)
					.raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	/** Command that uses {@code idempotencyKey(CallerKey.REQUIRED)} — requires external key. */
	static class RequireExternalKeyCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.idempotencyKey(CallerKey.REQUIRED)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code idempotencyKey(key, CallerKey.REFUSED)} — rejects external key. */
	static class ExclusiveKeyCommand implements Command<MockDomainEvent> {

		private final String key;

		ExclusiveKeyCommand(String key) {
			this.key = key;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.idempotencyKey(key, CallerKey.REFUSED)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code idempotencyKey(key, CallerKey.IGNORED)} — ignores external key. */
	static class IdempotencyKeyCommand implements Command<MockDomainEvent> {

		private final String key;

		IdempotencyKeyCommand(String key) {
			this.key = key;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.idempotencyKey(key, CallerKey.IGNORED)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	/** Command that uses {@code idempotencyKey(CallerKey.REFUSED)} — rejects any external key. */
	static class ForbidExternalKeyCommand implements Command<MockDomainEvent> {

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels()
					.idempotencyKey(CallerKey.REFUSED)
					.raiseEvent(new FirstDomainEvent("test"), Tags.none());
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: External idempotency key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void externalKey_firstExecution_storesEvent() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new SingleEventCommand("test"), Execution.idempotent("key-1"));

		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void externalKey_duplicateExecution_silentlyIgnored() {
		Mock domain = buildDomain();

		Optional<EventReference> first = domain.execute(new SingleEventCommand("test"), Execution.idempotent("key-1"));
		assertTrue(first.isPresent());

		Optional<EventReference> second = domain.execute(new SingleEventCommand("test"), Execution.idempotent("key-1"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void externalKey_differentKeys_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new SingleEventCommand("test-1"), Execution.idempotent("key-1"));
		domain.execute(new SingleEventCommand("test-2"), Execution.idempotent("key-2"));

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

		Optional<EventReference> result = domain.execute(new NoEventCommand(), Execution.idempotent("key-1"));

		assertTrue(result.isEmpty());
		assertEquals(0, countDomainEvents());
	}

	@Test
	void externalKey_multipleEvents_eachEventGetsAKeyDerivedFromIt() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new MultiEventCommand(), Execution.idempotent("key-1"));

		assertTrue(result.isPresent());
		assertEquals(2, countDomainEvents());
		// the shape the eventstore prescribes for a command whose one decision produces several events
		assertEquals(List.of("key-1/1", "key-1/2"), storedKeys());
	}

	@Test
	void externalKey_multipleEvents_retryIsSwallowedWhole() {
		Mock domain = buildDomain();

		assertTrue(domain.execute(new MultiEventCommand(), Execution.idempotent("key-1")).isPresent());
		Optional<EventReference> retry = domain.execute(new MultiEventCommand(), Execution.idempotent("key-1"));

		assertTrue(retry.isEmpty(), "every derived key was stored before, so the batch is a retry");
		assertEquals(2, countDomainEvents());
	}

	@Test
	void externalKey_multipleEvents_aBatchMixingStoredAndNewKeysIsRefusedWhole() {
		Mock domain = buildDomain();

		assertTrue(domain.execute(new MultiEventCommand(2), Execution.idempotent("key-1")).isPresent());

		// the same command key, a different set of events: key-1/1 and key-1/2 are stored, key-1/3 is
		// not, so this is not a retry -- and storing the third alone would land half a decision
		assertThrows(IdempotencyKeyConflictException.class,
				() -> domain.execute(new MultiEventCommand(3), Execution.idempotent("key-1")));
		assertEquals(2, countDomainEvents());
	}

	@Test
	void externalKey_multipleEvents_anEventKeyedByTheCommandKeepsItsOwnKey() {
		Mock domain = buildDomain();

		domain.execute(new PartlyKeyedMultiEventCommand(), Execution.idempotent("key-1"));

		// the derived key is spent on the position, so the second event's key does not shift to /1
		assertEquals(List.of("own-key", "key-1/2"), storedKeys());
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

		Optional<EventReference> first = domain.execute(cmd, Execution.idempotent("command-level-key"));
		assertTrue(first.isPresent());

		// event-level key preserved; re-executing deduplicates based on that key
		Optional<EventReference> second = domain.execute(cmd, Execution.idempotent("command-level-key"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey(key, PREFERRED) — internal fallback, external overrides
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
		domain.execute(new FallbackKeyCommand("test", "internal-key"), Execution.idempotent("external-key"));

		// second execution with same internal key but no external — not deduplicated
		// because the first event was stored with the external key
		Optional<EventReference> second = domain.execute(new FallbackKeyCommand("test", "internal-key"));
		assertTrue(second.isPresent());

		assertEquals(2, countDomainEvents());
	}

	@Test
	void fallbackKey_withExternalKey_duplicateExternal_deduplicated() {
		Mock domain = buildDomain();

		domain.execute(new FallbackKeyCommand("test", "internal-key"), Execution.idempotent("external-key"));

		// same external key deduplicates regardless of internal key
		Optional<EventReference> second = domain.execute(new FallbackKeyCommand("test", "different-internal"), Execution.idempotent("external-key"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey(REQUIRED) — requires external key
	// ════════════════════════════════════════════════════════════════════

	@Test
	void requireKey_withExternalKey_succeeds() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new RequireExternalKeyCommand(), Execution.idempotent("external-key"));
		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void requireKey_withoutExternalKey_throwsException() {
		Mock domain = buildDomain();

		assertThrows(IllegalStateException.class,
				() -> domain.execute(new RequireExternalKeyCommand()));
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey(key, REFUSED) — rejects external key
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

		assertThrows(IllegalStateException.class,
				() -> domain.execute(new ExclusiveKeyCommand("my-key"), Execution.idempotent("external-key")));
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey(key, IGNORED) — ignores external key
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
		domain.execute(new IdempotencyKeyCommand("my-key"), Execution.idempotent("external-key"));

		// same internal key, different external — still deduplicated by internal
		Optional<EventReference> second = domain.execute(new IdempotencyKeyCommand("my-key"), Execution.idempotent("different-external"));
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void idempotencyKey_differentInternalKeys_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new IdempotencyKeyCommand("key-a"), Execution.idempotent("same-external"));
		domain.execute(new IdempotencyKeyCommand("key-b"), Execution.idempotent("same-external"));

		// different internal keys means different events stored, despite same external
		assertEquals(2, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: idempotencyKey(REFUSED) — rejects external key
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

		assertThrows(IllegalStateException.class,
				() -> domain.execute(new ForbidExternalKeyCommand(), Execution.idempotent("external-key")));
	}

}
