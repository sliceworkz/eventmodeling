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

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Demonstrates that state a command reads through {@link CommandContext#read} is NOT part of the
 * consistency boundary the command appends under: the lock filter is built from the decision models
 * only, so an event the live read model was read from can be superseded concurrently and the append
 * still succeeds.
 */
public class CommandContextReadConsistencyTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> directStream;

	private Mock buildDomain ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));

		builder.readmodel(MockReadModel.class).live();

		Mock domain = buildBoundedContext(builder);

		directStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"), MockDomainEvent.class);

		return domain;
	}

	private void appendDirectly ( MockDomainEvent event ) {
		directStream.append(AppendCriteria.none(), Collections.singletonList(EphemeralEvent.of(event, Tags.none())));
	}

	/** A decision model over SecondDomainEvent — deliberately unrelated to what the command reads. */
	static class SecondCountingDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) { }
	}

	/**
	 * Reads a live read model over FirstDomainEvent, then lets a concurrent FirstDomainEvent land
	 * before appending. The append is expected to conflict — the decision was made on state the new
	 * event invalidates.
	 */
	private class ReadingCommand implements Command<MockDomainEvent> {

		private final boolean withUnrelatedDecisionModel;
		int countSeen;

		ReadingCommand ( boolean withUnrelatedDecisionModel ) {
			this.withUnrelatedDecisionModel = withUnrelatedDecisionModel;
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
			MockReadModel model = context.read(MockReadModel.class, "readForDecision", List.<Class<?>>of(FirstDomainEvent.class), ReadModelStorage.EPHEMERAL);
			countSeen = model.eventCount();

			// a concurrent writer appends a fact the read model would have shown, after we read it
			appendDirectly(new FirstDomainEvent("concurrent"));

			var result = withUnrelatedDecisionModel
					? context.decisionModels(new SecondCountingDecisionModel())
					: context.noDecisionModels();
			result.raiseEvent(new SecondDomainEvent("decided on a count of " + countSeen), Tags.none());
		}
	}

	@ForEachBackend
	void aCommandDecidingOnAReadModelAppendsWithNoConsistencyCheckAtAll ( ) {
		Mock domain = buildDomain();
		appendDirectly(new FirstDomainEvent("one"));

		ReadingCommand command = new ReadingCommand(false);
		// EXPECTED (DCB): OptimisticLockingException — the FirstDomainEvent appended after the read
		// is a new relevant fact. ACTUAL, asserted here: the append succeeds, on AppendCriteria.none().
		org.junit.jupiter.api.Assertions.assertTrue(domain.execute(command).isPresent());
		org.junit.jupiter.api.Assertions.assertEquals(1, command.countSeen);
	}

	/**
	 * Control: the very same event, the very same window, but the command reads it through a decision
	 * model instead of a read model. This one does conflict — which is what shows the difference is the
	 * read mechanism and not the test harness.
	 */
	@ForEachBackend
	void theSameEventReadThroughADecisionModelDoesConflict ( ) {
		Mock domain = buildDomain();
		appendDirectly(new FirstDomainEvent("one"));

		org.junit.jupiter.api.Assertions.assertThrows(
				org.sliceworkz.eventstore.stream.OptimisticLockingException.class,
				() -> domain.execute((Command<MockDomainEvent>) context -> {
					var result = context.decisionModels(new FirstCountingDecisionModel());
					appendDirectly(new FirstDomainEvent("concurrent"));
					result.raiseEvent(new SecondDomainEvent("decided"), Tags.none());
				}));
	}

	static class FirstCountingDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) { }
	}

	@ForEachBackend
	void aReadModelsEventsAreOutsideTheLockFilterEvenWhenDecisionModelsAreUsed ( ) {
		Mock domain = buildDomain();
		appendDirectly(new FirstDomainEvent("one"));

		ReadingCommand command = new ReadingCommand(true);
		// EXPECTED (DCB): OptimisticLockingException. ACTUAL, asserted here: the lock filter covers
		// SecondDomainEvent only, so the concurrent FirstDomainEvent is invisible to the check.
		org.junit.jupiter.api.Assertions.assertTrue(domain.execute(command).isPresent());
		org.junit.jupiter.api.Assertions.assertEquals(1, command.countSeen);
	}
}
