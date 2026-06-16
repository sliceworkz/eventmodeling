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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Tests for {@link CommandWithResult} execution through the DCB module.
 *
 * Verifies that commands implementing {@code CommandWithResult<D, R>} correctly
 * return a typed response value alongside the event reference after execution
 * and event persistence.
 */
public class DCBCommandWithResultTest extends AbstractMockDomainTest {

	private EventStorage eventStorage;
	private EventStream<MockDomainEvent> directStream;

	@BeforeEach
	protected void setUp() {
		super.setUp();
		this.eventStorage = createEventStorage();
	}

	@AfterEach
	protected void tearDown() {
		destroyEventStorage(eventStorage);
		if (boundedContext() != null) {
			boundedContext().stop();
		}
	}

	public EventStorage createEventStorage() {
		return InMemoryEventStorage.newBuilder().build();
	}

	public void destroyEventStorage(EventStorage storage) {
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

	static class SingleEventWithStringResponse implements CommandWithResult<MockDomainEvent, String> {

		private final String value;

		SingleEventWithStringResponse(String value) {
			this.value = value;
		}

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent(value), Tags.none());
			return "response:" + value;
		}
	}

	static class NoEventWithResponse implements CommandWithResult<MockDomainEvent, String> {

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
			return "no-events-response";
		}
	}

	static class MultiEventWithResponse implements CommandWithResult<MockDomainEvent, Integer> {

		@Override
		public Integer execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("first"), Tags.none());
			result.raiseEvent(new FirstDomainEvent("second"), Tags.none());
			return 42;
		}
	}

	static class CountingDecisionModel implements DecisionModel<MockDomainEvent> {

		private int count = 0;

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			if (event.data() instanceof FirstDomainEvent) {
				count++;
			}
		}

		public int count() { return count; }
	}

	static class DecisionModelCommandWithResult implements CommandWithResult<MockDomainEvent, Integer> {

		private final CountingDecisionModel model = new CountingDecisionModel();

		@Override
		public Integer execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.decisionModels(model);
			result.raiseEvent(new FirstDomainEvent("new"), Tags.none());
			return model.count();
		}
	}

	static class FallbackKeyCommandWithResult implements CommandWithResult<MockDomainEvent, String> {

		private final String value;
		private final String key;

		FallbackKeyCommandWithResult(String value, String key) {
			this.value = value;
			this.key = key;
		}

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.fallbackIdempotencyKey(key)
					.raiseEvent(new FirstDomainEvent(value), Tags.none());
			return "response:" + value;
		}
	}

	static class FailingCommandWithResult implements CommandWithResult<MockDomainEvent, String> {

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels();
			throw new RuntimeException("command execution failed");
		}
	}

	static class NullResponseCommand implements CommandWithResult<MockDomainEvent, String> {

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("test"), Tags.none());
			return null;
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Basic execution
	// ════════════════════════════════════════════════════════════════════

	@Test
	void basicExecution_returnsResponseAndEventReference() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> result = domain.execute(new SingleEventWithStringResponse("test"));

		assertEquals("response:test", result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void noEventsRaised_returnsResponseAndEmptyEventReference() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> result = domain.execute(new NoEventWithResponse());

		assertEquals("no-events-response", result.response());
		assertTrue(result.eventReference().isEmpty());
		assertEquals(0, countDomainEvents());
	}

	@Test
	void multipleEventsRaised_returnsResponseAndEventReference() {
		Mock domain = buildDomain();

		CommandExecutionResult<Integer> result = domain.execute(new MultiEventWithResponse());

		assertEquals(42, result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(2, countDomainEvents());
	}

	@Test
	void nullResponse_isAllowed() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> result = domain.execute(new NullResponseCommand());

		assertNull(result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Decision models
	// ════════════════════════════════════════════════════════════════════

	@Test
	void withDecisionModels_returnsResponse() {
		Mock domain = buildDomain();

		// Seed two existing events
		domain.execute(new SingleEventWithStringResponse("existing-1"));
		domain.execute(new SingleEventWithStringResponse("existing-2"));
		assertEquals(2, countDomainEvents());

		// Execute command with decision model that counts existing FirstDomainEvents
		CommandExecutionResult<Integer> result = domain.execute(new DecisionModelCommandWithResult());

		assertEquals(2, result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(3, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Idempotency key support
	// ════════════════════════════════════════════════════════════════════

	@Test
	void externalKey_firstExecution_returnsResponse() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> result = domain.execute(new SingleEventWithStringResponse("test"), "key-1");

		assertEquals("response:test", result.response());
		assertTrue(result.eventReference().isPresent());
		assertEquals(1, countDomainEvents());
	}

	@Test
	void externalKey_duplicateExecution_eventRefEmpty() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> first = domain.execute(new SingleEventWithStringResponse("test"), "key-1");
		assertTrue(first.eventReference().isPresent());

		CommandExecutionResult<String> second = domain.execute(new SingleEventWithStringResponse("test"), "key-1");
		assertTrue(second.eventReference().isEmpty());

		assertEquals(1, countDomainEvents());
	}

	@Test
	void fallbackKey_deduplicates_withResponse() {
		Mock domain = buildDomain();

		CommandExecutionResult<String> first = domain.execute(new FallbackKeyCommandWithResult("test", "internal-key"));
		assertEquals("response:test", first.response());
		assertTrue(first.eventReference().isPresent());

		// Duplicate with same internal key is deduplicated
		CommandExecutionResult<String> second = domain.execute(new FallbackKeyCommandWithResult("test", "internal-key"));
		assertEquals("response:test", second.response());
		assertTrue(second.eventReference().isEmpty());

		assertEquals(1, countDomainEvents());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Error handling
	// ════════════════════════════════════════════════════════════════════

	@Test
	void failingCommand_propagatesException() {
		Mock domain = buildDomain();

		RuntimeException cause = assertThrows(RuntimeException.class,
				() -> domain.execute(new FailingCommandWithResult()));

		assertEquals("command execution failed", cause.getMessage());
	}

}
