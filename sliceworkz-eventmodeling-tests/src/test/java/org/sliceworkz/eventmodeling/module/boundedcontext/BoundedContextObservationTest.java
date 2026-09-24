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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver.Recording;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * What the framework reports to a {@link BoundedContextObserver}: one observation per operation, with the
 * outcome the operation answered, nested where one operation runs inside another. These replace the
 * Micrometer meters the framework used to register — the command counter and timer, the per-event
 * counters of read models, translators and dispatchers, the automation batch meters, the live model
 * render meters — so what a metrics binding sees is pinned here, through a recording observer.
 * <p>
 * Every test also asserts that the framework kept the scope contract: each scope answered exactly once
 * and closed in order on the thread that opened it.
 */
public class BoundedContextObservationTest extends AbstractMockDomainTest {

	private static final String CONTEXT = "ObservedBoundedContext";

	private final RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();

	@AfterEach
	void theScopeContractWasKept ( ) {
		releaseBoundedContext();
		assertEquals(List.of(), observer.violations(), "the framework must answer every scope once and close it in order");
	}

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.observer(observer);
	}

	// ── commands ─────────────────────────────────────────────────────────────

	@Test
	void aCommandIsObservedWithWhatItRaisedAndAppended ( ) {
		Mock domain = buildBoundedContext(observedBuilder());

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests")).actor("alice").channel("web");
		domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two"), tracing);

		Recording execution = observer.last(Observation.CommandExecution.class);
		Observation.CommandExecution started = execution.observation(Observation.CommandExecution.class);
		assertEquals(CONTEXT, started.boundedContext());
		assertEquals("RaiseTwoEvents", started.command(), "the command's name, as every other report names it");
		assertEquals(CorrelationPropagationTest.RaiseTwoEventsCommand.class, started.commandClass());
		assertEquals(Observation.Target.DOMAIN, started.target());
		assertEquals("alice", started.tracing().actor());
		assertEquals("web", started.tracing().channel());
		assertEquals(tracing.correlationId(), started.tracing().correlationId(), "the caller's flow travels with the observation");

		Outcome.Executed executed = execution.outcome(Outcome.Executed.class);
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 2), executed.raisedPerType());
		assertEquals(2, executed.appended().size());
		assertTrue(execution.closed());
	}

	@Test
	void aConflictAndARejectionAreAnswersAndABugIsAFailure ( ) {
		Mock domain = buildBoundedContext(observedBuilder());

		assertThrows(OptimisticLockingException.class, () -> domain.execute(new ConflictingCommand(() -> domain.event(new FirstDomainEvent("meanwhile")))));
		Outcome.Conflicted conflicted = observer.last(Observation.CommandExecution.class).outcome(Outcome.Conflicted.class);
		assertNotNull(conflicted.expected());

		assertThrows(BusinessException.class, () -> domain.execute(new RejectingCommand()));
		assertEquals("not today", observer.last(Observation.CommandExecution.class).outcome(Outcome.Rejected.class).reason());

		IllegalStateException bug = assertThrows(IllegalStateException.class, () -> domain.execute(new BuggyCommand()));
		Recording failed = observer.last(Observation.CommandExecution.class);
		assertTrue(failed.outcome().isEmpty(), "a failure is not an answer");
		assertSame(bug, failed.failure().orElseThrow(), "the scope fails with what the caller receives");
	}

	@Test
	void anIdempotentRepeatIsExecutedWithNothingAppended ( ) {
		Mock domain = buildBoundedContext(observedBuilder());

		domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two"), "key-1");
		domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two"), "key-1");

		Outcome.Executed repeat = observer.last(Observation.CommandExecution.class).outcome(Outcome.Executed.class);
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 2), repeat.raisedPerType(), "what the command raised");
		assertEquals(List.of(), repeat.appended(), "and what storage swallowed");
	}

	@Test
	void anOutboundCommandTargetsTheOutboundStreamAndItsDispatchIsObserved ( ) {
		NamedDispatcher dispatcher = new NamedDispatcher();
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.dispatcher(dispatcher);
		Mock domain = buildBoundedContext(builder);

		domain.execute(new MockOutboundCommand("published"), "outbound/1");

		assertEquals(Observation.Target.OUTBOUND, observer.last(Observation.CommandExecution.class).observation(Observation.CommandExecution.class).target());

		waitBecauseOfEventualConsistency(() -> !observer.recordings(Observation.Dispatch.class).isEmpty()
				&& observer.last(Observation.Dispatch.class).closed());
		Recording dispatch = observer.last(Observation.Dispatch.class);
		Observation.Dispatch started = dispatch.observation(Observation.Dispatch.class);
		assertEquals("NamedDispatcher", started.dispatcher());
		assertEquals(EventType.of(SomeOutboundEvent.class), started.eventType());
		assertEquals(Outcome.Done.INSTANCE, dispatch.outcome(Outcome.Done.class));
		assertFalse(dispatch.thread().equals(Thread.currentThread().getName()), "a dispatch runs on its processor's thread");
	}

	// ── provided and inbound events ─────────────────────────────────────────

	@Test
	void aProvidedEventIsObservedAndARepeatIsProvidedAsNothing ( ) {
		Mock domain = buildBoundedContext(observedBuilder());

		Optional<EventReference> first = domain.event(new FirstDomainEvent("provided"), "provided-1");
		assertEquals(first, observer.last(Observation.ProvidedEvent.class).outcome(Outcome.Provided.class).reference());
		assertEquals(EventType.of(FirstDomainEvent.class), observer.last(Observation.ProvidedEvent.class).observation(Observation.ProvidedEvent.class).eventType());

		domain.event(new FirstDomainEvent("provided"), "provided-1");
		assertEquals(Optional.empty(), observer.last(Observation.ProvidedEvent.class).outcome(Outcome.Provided.class).reference());
	}

	@Test
	void anIncomingEventIsObservedAndTranslatedInItsOwnFlow ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.translator(new CorrelationPropagationTest.SomeInboundTranslator());
		Mock domain = buildBoundedContext(builder);

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests")).actor("partner").channel("api");
		domain.incoming(new SomeInboundEvent("in"), tracing);

		Recording incoming = observer.last(Observation.IncomingEvent.class);
		assertEquals(EventType.of(SomeInboundEvent.class), incoming.observation(Observation.IncomingEvent.class).eventType());
		incoming.outcome(Outcome.Done.class);

		waitBecauseOfEventualConsistency(() -> !observer.recordings(Observation.TranslatorInvocation.class).isEmpty()
				&& observer.last(Observation.TranslatorInvocation.class).closed());
		Observation.TranslatorInvocation invocation = observer.last(Observation.TranslatorInvocation.class).observation(Observation.TranslatorInvocation.class);
		assertEquals("SomeInboundTranslator", invocation.translator());
		assertEquals(EventType.of(SomeInboundEvent.class), invocation.eventType());
		assertEquals(tracing.correlationId(), invocation.tracing().correlationId(), "the translation continues the inbound event's flow");
	}

	@Test
	void anInteractiveTranslationNestsItsTranslatorsAndAnswersWhatTheyRaised ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.translator(new CorrelationPropagationTest.SomeInboundTranslator());
		Mock domain = buildBoundedContext(builder);

		List<EventReference> raised = domain.translate(new SomeInboundEvent("now"));

		Recording translation = observer.last(Observation.Translation.class);
		assertEquals(raised, translation.outcome(Outcome.Translated.class).raised());
		Recording invocation = observer.last(Observation.TranslatorInvocation.class);
		assertSame(translation, invocation.parent().orElseThrow(), "each translator runs inside the translation");
		assertSame(invocation, observer.last(Observation.ProvidedEvent.class).parent().orElseThrow(), "and what it raises inside it");
	}

	// ── read models ──────────────────────────────────────────────────────────

	@Test
	void anEventuallyConsistentReadModelIsObservedPerBatchAndPerEvent ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(new MockReadModel("observed-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two"));

		waitBecauseOfEventualConsistency(() -> updates("observed-readmodel") >= 2
				&& observer.recordings(Observation.ReadModelBatch.class).stream().allMatch(Recording::closed));

		for ( Recording update : observer.recordings(Observation.ReadModelUpdate.class) ) {
			Observation.ReadModelUpdate started = update.observation(Observation.ReadModelUpdate.class);
			assertEquals(ReadModelStorage.EPHEMERAL, started.storage());
			assertEquals(EventType.of(FirstDomainEvent.class), started.eventType());
			Recording batch = update.parent().orElseThrow();
			assertTrue(batch.observation() instanceof Observation.ReadModelBatch, "an update nests in its batch: " + batch);
		}
		long handled = observer.recordings(Observation.ReadModelBatch.class).stream()
				.map(batch -> batch.outcome(Outcome.BatchResult.class))
				.mapToLong(result -> result instanceof Outcome.Projected projected ? projected.eventsHandled() : 0)
				.sum();
		assertEquals(2, handled, "the batches account for every event handed to the read model");
	}

	@Test
	void aLiveModelReadIsObservedWithWhatItProjected ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(MockReadModel.class).live();
		Mock domain = buildBoundedContext(builder);

		domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two"));
		domain.read(MockReadModel.class, "live");

		Recording read = observer.last(Observation.LiveModelRead.class);
		Observation.LiveModelRead started = read.observation(Observation.LiveModelRead.class);
		assertEquals("MockReadModel", started.readModel());
		assertFalse(started.unbounded());
		Outcome.LiveModelProjected projected = read.outcome(Outcome.LiveModelProjected.class);
		assertEquals(2, projected.eventsStreamed());
		assertEquals(2, projected.eventsHandled());
		assertEquals(Optional.empty(), projected.startedAfter(), "neither seeded nor snapshotted: a full replay");
		assertTrue(projected.until().isPresent());
	}

	// ── automations ──────────────────────────────────────────────────────────

	@Test
	void anAutomationRunIsObservedWithWhatItHandledAndWhatItRaisedNestsInside ( ) {
		CorrelationPropagationTest.CorrelatedTodoList todoList = new CorrelationPropagationTest.CorrelatedTodoList();
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new CorrelationPropagationTest.RecordingAutomation(todoList));
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("work"));

		waitBecauseOfEventualConsistency(() -> observer.recordings(Observation.AutomationRun.class).stream()
				.anyMatch(run -> run.closed() && run.outcome(Outcome.AutomationRan.class).itemsHandled() == 1));

		Recording run = observer.recordings(Observation.AutomationRun.class).stream()
				.filter(r -> r.closed() && r.outcome(Outcome.AutomationRan.class).itemsHandled() == 1).findFirst().orElseThrow();
		assertEquals("RecordingAutomation", run.observation(Observation.AutomationRun.class).automation());
		Outcome.AutomationRan ran = run.outcome(Outcome.AutomationRan.class);
		assertEquals(1, ran.itemsStreamed());
		assertEquals(0, ran.itemsFailed());
		assertTrue(ran.lastProduced().isPresent());

		Recording raised = observer.recordings(Observation.ProvidedEvent.class).stream()
				.filter(r -> r.observation(Observation.ProvidedEvent.class).eventType().equals(EventType.of(SecondDomainEvent.class)))
				.findFirst().orElseThrow();
		assertSame(run, raised.parent().orElseThrow(), "what the automation raised is observed inside its run");
	}

	// ── containment ──────────────────────────────────────────────────────────

	@Test
	void anObserverThatThrowsNeverFailsTheWork ( ) {
		BoundedContextObserver throwing = new BoundedContextObserver() {
			@Override
			public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
				throw new IllegalStateException("broken binding");
			}
		};
		Mock domain = buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.observer(throwing));

		assertTrue(domain.execute(new CorrelationPropagationTest.RaiseTwoEventsCommand("one", "two")).isPresent(),
				"the command appends as if nothing observed it");
	}

	@Test
	void aNullObserverIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> BoundedContext.newBuilder(Mock.class).observer(null));
		assertThrows(IllegalArgumentException.class, () -> BoundedContext.newBuilder(Mock.class).eventStoreObserver(null));
	}

	private long updates ( String readModel ) {
		return observer.observations(Observation.ReadModelUpdate.class).stream().filter(u -> u.readModel().equals(readModel)).count();
	}

	// ── components ───────────────────────────────────────────────────────────

	static class CountingDecisionModel implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) { }
	}

	/** Reads its boundary, lets a concurrent writer land a matching event after it, then appends. */
	static class ConflictingCommand implements Command<MockDomainEvent> {
		private final Runnable concurrentWrite;

		ConflictingCommand ( Runnable concurrentWrite ) {
			this.concurrentWrite = concurrentWrite;
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			var result = context.decisionModels(new CountingDecisionModel());
			concurrentWrite.run();
			result.raiseEvent(new SecondDomainEvent("stale"), Tags.none());
		}
	}

	static class RejectingCommand implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.noDecisionModels();
			throw new BusinessException("not today");
		}
	}

	static class BuggyCommand implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.noDecisionModels();
			throw new IllegalStateException("a bug");
		}
	}

	static class NamedDispatcher implements Dispatcher<MockOutboundEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeOutboundEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockOutboundEvent> event ) { }
	}

}
