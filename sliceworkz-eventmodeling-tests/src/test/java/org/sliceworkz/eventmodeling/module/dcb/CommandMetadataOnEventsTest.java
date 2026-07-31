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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Verifies the {@code x-command} metadata tag added to events that are raised
 * as the result of a command, across all command flavors and the aggregate path.
 * Events provided directly via {@link BoundedContext#event} carry no {@code x-command} tag.
 */
public class CommandMetadataOnEventsTest extends AbstractMockDomainTest {

	private static final String X_COMMAND = "x-command";

	private EventStream<MockDomainEvent> domainStream;
	private EventStream<MockOutboundEvent> outboundStream;

	private Mock buildDomain() {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.aggregate(CounterAggregate.class);

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

	@Test
	void commandFlavor_addsXCommandTag() {
		Mock domain = buildDomain();

		domain.execute(new RaiseDomainCommand("v1"));

		var events = domainEvents();
		assertEquals(1, events.size());
		assertTrue(events.get(0).tags().tag(X_COMMAND).isPresent());
		assertEquals("RaiseDomain", events.get(0).tags().tag(X_COMMAND).get().value());
	}

	@Test
	void commandWithResultFlavor_addsXCommandTag() {
		Mock domain = buildDomain();

		domain.execute(new RaiseDomainCommandWithResult("v1"));

		var events = domainEvents();
		assertEquals(1, events.size());
		assertEquals("RaiseDomainCommandWithResult", events.get(0).tags().tag(X_COMMAND).get().value());
	}

	@Test
	void outboundCommandFlavor_addsXCommandTag() {
		Mock domain = buildDomain();

		domain.execute(new RaiseOutboundCommand("v1"));

		var events = outboundEvents();
		assertEquals(1, events.size());
		assertEquals("RaiseOutbound", events.get(0).tags().tag(X_COMMAND).get().value());
	}

	@Test
	void commandNameWithoutCommandSuffix_isUsedAsIs() {
		Mock domain = buildDomain();

		domain.execute(new RaiseDomainAction("v1"));

		var events = domainEvents();
		assertEquals(1, events.size());
		assertEquals("RaiseDomainAction", events.get(0).tags().tag(X_COMMAND).get().value());
	}

	@Test
	void providedEvent_doesNotAddXCommandTag() {
		Mock domain = buildDomain();

		domain.event(new FirstDomainEvent("provided"));

		var events = domainEvents();
		assertEquals(1, events.size());
		assertFalse(events.get(0).tags().tag(X_COMMAND).isPresent());
	}

	@Test
	void providedEventWithExplicitTracing_doesNotAddXCommandTag() {
		Mock domain = buildDomain();

		domain.event(new FirstDomainEvent("provided"),
				Tracing.actorAndChannel("alice", "api"));

		var events = domainEvents();
		assertEquals(1, events.size());
		assertFalse(events.get(0).tags().tag(X_COMMAND).isPresent());
	}

	@Test
	void aggregateRaisedEvents_carryXCommandWhenTracingHasIt() {
		Mock domain = buildDomain();

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests"))
				.command("SomeOuterCommand");

		CounterAggregate aggregate = domain.aggregate(CounterAggregate.class,
				Tags.of("businessObject", "abc"), tracing);
		aggregate.bump();

		var events = domainEvents();
		assertEquals(1, events.size());
		assertEquals("SomeOuterCommand", events.get(0).tags().tag(X_COMMAND).get().value());
	}

	@Test
	void aggregateRaisedEvents_haveNoXCommandWhenNotSet() {
		Mock domain = buildDomain();

		CounterAggregate aggregate = domain.aggregate(CounterAggregate.class,
				Tags.of("businessObject", "abc"));
		aggregate.bump();

		var events = domainEvents();
		assertEquals(1, events.size());
		assertFalse(events.get(0).tags().tag(X_COMMAND).isPresent());
	}

	// ════════════════════════════════════════════════════════════════════
	// COMMAND IMPLEMENTATIONS
	// ════════════════════════════════════════════════════════════════════

	static class RaiseDomainCommand implements Command<MockDomainEvent> {

		private final String value;

		RaiseDomainCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels().raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	static class RaiseDomainCommandWithResult implements CommandWithResult<MockDomainEvent, String> {

		private final String value;

		RaiseDomainCommandWithResult(String value) {
			this.value = value;
		}

		@Override
		public String execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels().raiseEvent(new FirstDomainEvent(value), Tags.none());
			return "ok:" + value;
		}
	}

	static class RaiseDomainAction implements Command<MockDomainEvent> {

		private final String value;

		RaiseDomainAction(String value) {
			this.value = value;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			context.noDecisionModels().raiseEvent(new FirstDomainEvent(value), Tags.none());
		}
	}

	static class RaiseOutboundCommand implements OutboundCommand<MockDomainEvent, MockOutboundEvent> {

		private final String value;

		RaiseOutboundCommand(String value) {
			this.value = value;
		}

		@Override
		public void execute(CommandContext<MockDomainEvent, MockOutboundEvent> context) {
			context.noDecisionModels().raiseEvent(new SomeOutboundEvent(value), Tags.none());
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// AGGREGATE
	// ════════════════════════════════════════════════════════════════════

	public static class CounterAggregate implements Aggregate<MockDomainEvent> {

		private AggregateContext<MockDomainEvent> ctx;
		private int counter;

		public CounterAggregate() {
		}

		public void bump() {
			ctx.raiseEvent(new FirstDomainEvent("bump-" + counter));
		}

		@Override
		public void when(MockDomainEvent event) {
			counter++;
		}

		@Override
		public void setContext(AggregateContext<MockDomainEvent> aggregateContext) {
			this.ctx = aggregateContext;
		}
	}

}
