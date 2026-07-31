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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.DecisionModelProjected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * Tests that a {@link DecisionModelProjected} bounded-context event is emitted for each decision
 * model used by a command, carrying that model's own physical projector read ({@code eventsStreamed})
 * and the subset it handled ({@code eventsHandled}).
 */
public class DecisionModelProjectedTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "DecisionModelProjectedBoundedContext";

	private Mock buildDomain(BoundedContextListener listener) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(listener);
		return buildBoundedContext(builder);
	}

	// ── decision models ────────────────────────────────────────────────────

	static class CountingFirstDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
		}
	}

	static class CountingSecondDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
		}
	}

	// ── commands ───────────────────────────────────────────────────────────

	static class TwoModelsCommand implements Command<MockDomainEvent> {
		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.decisionModels(new CountingFirstDecisionModel(), new CountingSecondDecisionModel());
			result.raiseEvent(new FirstDomainEvent("raised"), Tags.none());
		}
	}

	static class NoModelsCommand implements Command<MockDomainEvent> {
		@Override
		public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			result.raiseEvent(new FirstDomainEvent("raised"), Tags.none());
		}
	}

	// ── tests ──────────────────────────────────────────────────────────────

	@Test
	void emitsADecisionModelProjectedPerDecisionModel() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		// two events the first model cares about, three the second cares about
		domain.event(new FirstDomainEvent("f1"));
		domain.event(new FirstDomainEvent("f2"));
		domain.event(new SecondDomainEvent("s1"));
		domain.event(new SecondDomainEvent("s2"));
		domain.event(new SecondDomainEvent("s3"));

		domain.execute(new TwoModelsCommand());

		List<DecisionModelProjected> projected = received.stream()
				.filter(e -> e instanceof DecisionModelProjected)
				.map(e -> (DecisionModelProjected) e)
				.toList();

		assertEquals(2, projected.size(), "expected one DecisionModelProjected per decision model, got: " + received);

		DecisionModelProjected first = projected.stream()
				.filter(p -> p.decisionModel().equals(CountingFirstDecisionModel.class.getSimpleName()))
				.findFirst().orElseThrow(() -> new AssertionError("missing first model event: " + projected));
		DecisionModelProjected second = projected.stream()
				.filter(p -> p.decisionModel().equals(CountingSecondDecisionModel.class.getSimpleName()))
				.findFirst().orElseThrow(() -> new AssertionError("missing second model event: " + projected));

		assertEquals(CONTEXT_NAME, first.boundedContext());
		assertNotNull(first.metrics());

		// the two plain models are reduced to a single merged read, so eventsStreamed is the shared
		// unified count (the 2 FirstDomainEvents + 3 SecondDomainEvents) on both events
		assertEquals(5, first.metrics().eventsStreamed(), "streamed is the unified merged read count");
		assertEquals(first.metrics().eventsStreamed(), second.metrics().eventsStreamed(),
				"both decision models share the same merged read, so the same eventsStreamed");

		// eventsHandled is the subset relevant to each model
		assertEquals(2, first.metrics().eventsHandled(), "first model handled the 2 FirstDomainEvents");
		assertEquals(3, second.metrics().eventsHandled(), "second model handled the 3 SecondDomainEvents");
	}

	@Test
	void decisionModelProjectedIsEmittedBeforeCommandExecuted() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		domain.event(new FirstDomainEvent("f1"));

		domain.execute(new TwoModelsCommand());

		int firstProjected = indexOfFirst(received, DecisionModelProjected.class);
		int commandExecuted = indexOfFirst(received, CommandExecuted.class);

		assertTrue(firstProjected >= 0, "expected a DecisionModelProjected event, got: " + received);
		assertTrue(commandExecuted >= 0, "expected a CommandExecuted event, got: " + received);
		assertTrue(firstProjected < commandExecuted,
				"DecisionModelProjected should be emitted before CommandExecuted, got: " + received);
	}

	@Test
	void noDecisionModelProjectedWhenCommandUsesNoDecisionModels() {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		Mock domain = buildDomain(event -> received.add(event.data()));

		domain.execute(new NoModelsCommand());

		assertTrue(received.stream().noneMatch(e -> e instanceof DecisionModelProjected),
				"expected no DecisionModelProjected events, got: " + received);
		assertTrue(received.stream().anyMatch(e -> e instanceof CommandExecuted),
				"expected a CommandExecuted event, got: " + received);
	}

	private static int indexOfFirst(List<BoundedContextEvent> events, Class<? extends BoundedContextEvent> type) {
		for (int i = 0; i < events.size(); i++) {
			if (type.isInstance(events.get(i))) {
				return i;
			}
		}
		return -1;
	}
}
