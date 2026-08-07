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

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.module.automation.AutomationContextImpl;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins the guard rails around {@link OutboundCommand} and the {@code publishAndRecord} composition:
 * an outbound event without an idempotency key is rejected before anything is stored (a key from any
 * source satisfies the guard, {@code forbidIdempotencyKey()} is the deliberate opt-out), and
 * {@code AutomationContext.publishAndRecord} appends outbound first and domain second under
 * item-derived keys, so re-handling the same item appends nothing new. The remaining guard is
 * compile-time and has no runtime test: an {@code OutboundCommand} receives an
 * {@link OutboundCommandContext}, which offers no {@code decisionModels(...)} to mistakenly rely on.
 * <p>
 * Plain {@code @Test}s, since this is framework behaviour rather than storage behaviour.
 */
public class OutboundCommandGuardsTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> domainStream;
	private EventStream<MockOutboundEvent> outboundStream;

	private Mock buildDomain() {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));

		Mock domain = buildBoundedContext(builder);

		domainStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"),
						MockDomainEvent.class);
		outboundStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("outbound"),
						MockOutboundEvent.class);

		return domain;
	}

	private List<? extends Event<MockDomainEvent>> domainEvents() {
		return domainStream.query(EventQuery.matchAll()).toList();
	}

	private List<? extends Event<MockOutboundEvent>> outboundEvents() {
		return outboundStream.query(EventQuery.matchAll()).toList();
	}

	private AutomationContext<MockDomainEvent, MockOutboundEvent> automationContext(Mock domain) {
		return new AutomationContextImpl<>(domain, Tracing.init(InstanceFactory.determine("unittests")));
	}

	// ════════════════════════════════════════════════════════════════════
	// THE IDEMPOTENCY KEY REQUIREMENT
	// ════════════════════════════════════════════════════════════════════

	@Test
	void anOutboundEventWithoutAnyIdempotencyKeyIsRejectedBeforeAnythingIsStored() {
		Mock domain = buildDomain();

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> domain.execute(new UnkeyedOutboundCommand("v1")));

		assertTrue(failure.getMessage().contains("idempotency key"), failure.getMessage());
		assertTrue(failure.getMessage().contains("UnkeyedOutbound"), failure.getMessage());
		assertEquals(0, outboundEvents().size());
	}

	@Test
	void aPerEventKeySatisfiesTheGuardAndDeduplicatesTheRetry() {
		Mock domain = buildDomain();

		domain.execute(new SelfKeyingOutboundCommand("v1"));
		domain.execute(new SelfKeyingOutboundCommand("v1"));

		assertEquals(1, outboundEvents().size());
	}

	@Test
	void anExternallyProvidedKeySatisfiesTheGuardAndDeduplicatesTheRetry() {
		Mock domain = buildDomain();

		domain.execute(new UnkeyedOutboundCommand("v1"), "external/1");
		domain.execute(new UnkeyedOutboundCommand("v1"), "external/1");

		assertEquals(1, outboundEvents().size());
	}

	@Test
	void forbidIdempotencyKeyIsTheDeliberateOptOut() {
		Mock domain = buildDomain();

		domain.execute(new OptedOutOutboundCommand("v1"));
		domain.execute(new OptedOutOutboundCommand("v1"));

		// no keys, so no de-duplication either — that is what the opt-out costs
		assertEquals(2, outboundEvents().size());
	}

	// ════════════════════════════════════════════════════════════════════
	// PUBLISH-AND-RECORD
	// ════════════════════════════════════════════════════════════════════

	@Test
	void publishAndRecordAppendsOutboundFirstThenDomainAndReturnsTheDomainReference() {
		Mock domain = buildDomain();

		Optional<EventReference> reference = automationContext(domain).publishAndRecord(
				new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded"), "item/1");

		assertEquals(1, outboundEvents().size());
		assertEquals(1, domainEvents().size());
		assertEquals(domainEvents().get(0).reference(), reference.orElseThrow());
		// the ordering is the crash-safety guarantee: the domain event, which completes the todo
		// item, must land after the publication it records
		assertTrue(domainEvents().get(0).reference().happenedAfter(outboundEvents().get(0).reference()));
	}

	@Test
	void publishAndRecordHandedTheSameItemAgainAppendsNothingNew() {
		Mock domain = buildDomain();
		AutomationContext<MockDomainEvent, MockOutboundEvent> context = automationContext(domain);

		context.publishAndRecord(new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded"), "item/1");
		Optional<EventReference> secondRound = context.publishAndRecord(
				new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded"), "item/1");

		assertEquals(1, outboundEvents().size());
		assertEquals(1, domainEvents().size());
		// empty means "already recorded", which for an at-least-once caller is success
		assertTrue(secondRound.isEmpty());
	}

	@Test
	void publishAndRecordUnderDistinctItemKeysDoesNotCrossDeduplicate() {
		Mock domain = buildDomain();
		AutomationContext<MockDomainEvent, MockOutboundEvent> context = automationContext(domain);

		context.publishAndRecord(new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded-1"), "item/1");
		context.publishAndRecord(new UnkeyedOutboundCommand("v2"), new FirstDomainEvent("recorded-2"), "item/2");

		assertEquals(2, outboundEvents().size());
		assertEquals(2, domainEvents().size());
	}

	@Test
	void publishAndRecordRejectsAMissingItemKey() {
		Mock domain = buildDomain();
		AutomationContext<MockDomainEvent, MockOutboundEvent> context = automationContext(domain);

		assertThrows(IllegalArgumentException.class, () -> context.publishAndRecord(
				new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded"), null));
		assertThrows(IllegalArgumentException.class, () -> context.publishAndRecord(
				new UnkeyedOutboundCommand("v1"), new FirstDomainEvent("recorded"), "  "));

		assertEquals(0, outboundEvents().size());
		assertEquals(0, domainEvents().size());
	}

	// ════════════════════════════════════════════════════════════════════
	// COMMAND IMPLEMENTATIONS
	// ════════════════════════════════════════════════════════════════════

	/** Raises an unkeyed outbound event: rejected on its own, fine under an externally provided key. */
	static class UnkeyedOutboundCommand implements OutboundCommand<MockDomainEvent, MockOutboundEvent> {

		private final String value;

		UnkeyedOutboundCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(OutboundCommandContext<MockDomainEvent, MockOutboundEvent> context) {
			context.noDecisionModels().raiseEvent(new SomeOutboundEvent(value), Tags.none());
		}
	}

	/** Keys its event itself, from what it publishes for. */
	static class SelfKeyingOutboundCommand implements OutboundCommand<MockDomainEvent, MockOutboundEvent> {

		private final String value;

		SelfKeyingOutboundCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(OutboundCommandContext<MockDomainEvent, MockOutboundEvent> context) {
			context.noDecisionModels().raiseEvent(new SomeOutboundEvent(value), Tags.none(), "self/" + value);
		}
	}

	/** Publishes without de-duplication on purpose. */
	static class OptedOutOutboundCommand implements OutboundCommand<MockDomainEvent, MockOutboundEvent> {

		private final String value;

		OptedOutOutboundCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(OutboundCommandContext<MockDomainEvent, MockOutboundEvent> context) {
			context.noDecisionModels()
					.forbidIdempotencyKey()
					.raiseEvent(new SomeOutboundEvent(value), Tags.none());
		}
	}

}
