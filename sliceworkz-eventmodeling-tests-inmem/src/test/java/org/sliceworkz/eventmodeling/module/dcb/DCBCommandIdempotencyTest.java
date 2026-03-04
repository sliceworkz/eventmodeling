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
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
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
 * Tests for command-level idempotency support on the execute() method.
 * When a command is executed with an idempotency key, the framework applies
 * the key to the raised event(s). Duplicate executions with the same key
 * are silently ignored by the event store.
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
		public CommandResult<MockDomainEvent, MockDomainEvent> execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			return result.raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	static class MultiEventCommand implements Command<MockDomainEvent> {

		@Override
		public CommandResult<MockDomainEvent, MockDomainEvent> execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("first"), Tags.none());
			result.raiseEvent(new FirstDomainEvent("second"), Tags.none());
			return result;
		}
	}

	static class NoEventCommand implements Command<MockDomainEvent> {

		@Override
		public CommandResult<MockDomainEvent, MockDomainEvent> execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			return context.noDecisionModels();
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS
	// ════════════════════════════════════════════════════════════════════

	@Test
	void executeWithIdempotencyKey_firstExecution_storesEvent() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new SingleEventCommand("test"), "key-1");

		assertTrue(result.isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void executeWithIdempotencyKey_duplicateExecution_silentlyIgnored() {
		Mock domain = buildDomain();

		Optional<EventReference> first = domain.execute(new SingleEventCommand("test"), "key-1");
		assertTrue(first.isPresent());

		// second execution with same key
		Optional<EventReference> second = domain.execute(new SingleEventCommand("test"), "key-1");
		assertTrue(second.isEmpty());

		// only one event stored
		assertEquals(1, countDomainEvents());
	}

	@Test
	void executeWithIdempotencyKey_differentKeys_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new SingleEventCommand("test-1"), "key-1");
		domain.execute(new SingleEventCommand("test-2"), "key-2");

		assertEquals(2, countDomainEvents());
	}

	@Test
	void executeWithoutIdempotencyKey_duplicateCommands_bothStored() {
		Mock domain = buildDomain();

		domain.execute(new SingleEventCommand("test"));
		domain.execute(new SingleEventCommand("test"));

		assertEquals(2, countDomainEvents());
	}

	@Test
	void executeWithIdempotencyKey_noEventsRaised_returnsEmpty() {
		Mock domain = buildDomain();

		Optional<EventReference> result = domain.execute(new NoEventCommand(), "key-1");

		assertTrue(result.isEmpty());
		assertEquals(0, countDomainEvents());
	}

	@Test
	void executeWithIdempotencyKey_multipleEvents_throwsException() {
		Mock domain = buildDomain();

		UndeclaredThrowableException e = assertThrows(UndeclaredThrowableException.class,
				() -> domain.execute(new MultiEventCommand(), "key-1"));

		Throwable cause = e.getCause().getCause();
		assertTrue(cause instanceof IllegalArgumentException,
				"Expected IllegalArgumentException but got: " + cause.getClass().getName());
	}

	@Test
	void executeWithIdempotencyKey_eventAlreadyHasKey_preservesEventKey() {
		Mock domain = buildDomain();

		// command that sets its own idempotency key on the event
		Command<MockDomainEvent> cmd = new Command<>() {
			@Override
			public CommandResult<MockDomainEvent, MockDomainEvent> execute(
					CommandContext<MockDomainEvent, MockDomainEvent> context) {
				var result = context.noDecisionModels();
				return result.raiseEvent(new FirstDomainEvent("test"), Tags.none(), "event-level-key");
			}
		};

		// first execution with event-level key
		Optional<EventReference> first = domain.execute(cmd, "command-level-key");
		assertTrue(first.isPresent());

		// second execution - the event-level key was preserved (not overwritten by command key),
		// so re-executing with same event-level key should be deduplicated
		Optional<EventReference> second = domain.execute(cmd, "command-level-key");
		assertTrue(second.isEmpty());

		assertEquals(1, countDomainEvents());
	}

}
