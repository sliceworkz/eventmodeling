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

import java.util.Collections;

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
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Tests for DCBCommandContextImpl focusing on decision models with and without
 * initQuery, optimistic locking with multiple decision models, and correct
 * state population after the per-model Projector refactoring.
 * <p>
 * DCB optimistic locking works by comparing the lastEventReference captured
 * during decision model projection against the actual stream state at append time.
 * If new events matching the combined eventQuery filter appeared after that
 * reference, an {@link OptimisticLockingException} is thrown. To test this in
 * a single-threaded environment, conflicting events must be injected DURING
 * command execution (between reading decision models and the final append).
 */
public class DCBDecisionModelTest extends AbstractMockDomainTest {

	private EventStream<MockDomainEvent> directStream;

	private Mock buildDomain() {
		var builder =
				BoundedContext.newBuilder(Mock.class)
						.name("UnitTestBoundedContext")
						.eventStorage(eventStorage())
						.instance(InstanceFactory.determine("unittests"));

		Mock domain = buildBoundedContext(builder);

		// Create a direct event stream for injecting concurrent events during command execution
		directStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(
						EventStreamId.forContext("UnitTestBoundedContext").withPurpose("domain"),
						MockDomainEvent.class);

		return domain;
	}

	/**
	 * Appends an event directly to the stream, bypassing the bounded context.
	 * Used to simulate concurrent modification during command execution.
	 */
	private void appendDirectly(MockDomainEvent event) {
		directStream.append(
				AppendCriteria.none(),
				Collections.singletonList(EphemeralEvent.of(event, Tags.none()))
		);
	}

	/**
	 * Asserts that executing the given runnable throws an OptimisticLockingException.
	 * The bounded context proxy propagates the real exception to the caller.
	 */
	private void assertOptimisticLockingException(Runnable action) {
		assertThrows(OptimisticLockingException.class, action::run);
	}

	// ════════════════════════════════════════════════════════════════════
	// DECISION MODELS
	// ════════════════════════════════════════════════════════════════════

	/**
	 * Classic decision model (no initQuery) that counts FirstDomainEvent occurrences.
	 */
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

	/**
	 * Classic decision model (no initQuery) that counts SecondDomainEvent occurrences.
	 */
	static class SecondCountingDecisionModel implements DecisionModel<MockDomainEvent> {

		private int count = 0;

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			if (event.data() instanceof SecondDomainEvent) {
				count++;
			}
		}

		public int count() { return count; }
	}

	/**
	 * Classic decision model (First events) that runs the given action exactly once, the first time it
	 * handles an event. Used to inject a concurrent append DURING this model's projection — i.e. after
	 * the command's consistency boundary has been pinned but before a later decision model is read.
	 */
	static class InjectingFirstDecisionModel implements DecisionModel<MockDomainEvent> {

		private final Runnable injectOnce;
		private boolean injected = false;

		InjectingFirstDecisionModel(Runnable injectOnce) {
			this.injectOnce = injectOnce;
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			if (!injected) {
				injected = true;
				injectOnce.run();
			}
		}
	}

	/**
	 * Savepoint decision model (with initQuery). Uses ThirdDomainEvent as a
	 * savepoint carrying a running total, then counts SecondDomainEvents after it.
	 */
	static class SavepointDecisionModel implements DecisionModel<MockDomainEvent> {

		private int count = 0;

		@Override
		public EventQuery initQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none())
					.backwards().limit(1);
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			switch (event.data()) {
				case ThirdDomainEvent t -> count = Integer.parseInt(t.value());
				case SecondDomainEvent s -> count++;
				default -> {}
			}
		}

		public int count() { return count; }
	}

	// ════════════════════════════════════════════════════════════════════
	// COMMANDS
	// ════════════════════════════════════════════════════════════════════

	/**
	 * Command with a single classic decision model (no initQuery).
	 */
	static class SingleModelCommand implements Command<MockDomainEvent> {

		private final int maxAllowed;
		CountingDecisionModel model;

		SingleModelCommand(int maxAllowed) {
			this.maxAllowed = maxAllowed;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			model = new CountingDecisionModel();
			var result = context.decisionModels(model);

			if (model.count() >= maxAllowed) {
				throw new IllegalStateException("Max " + maxAllowed + " events allowed, have " + model.count());
			}

			result.raiseEvent(new FirstDomainEvent("event-" + (model.count() + 1)), Tags.none());
		}

		public CountingDecisionModel model() { return model; }
	}

	/**
	 * Command with a single savepoint decision model (with initQuery).
	 */
	static class SavepointModelCommand implements Command<MockDomainEvent> {

		SavepointDecisionModel model;

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			model = new SavepointDecisionModel();
			var result = context.decisionModels(model);
			result.raiseEvent(new SecondDomainEvent("movement-" + (model.count() + 1)), Tags.none());
		}

		public SavepointDecisionModel model() { return model; }
	}

	/**
	 * Command with two classic decision models (no initQuery) watching different event types.
	 */
	static class TwoClassicModelsCommand implements Command<MockDomainEvent> {

		private CountingDecisionModel firstModel;
		private SecondCountingDecisionModel secondModel;
		private final Runnable afterDecisionModels;

		TwoClassicModelsCommand() {
			this(() -> {});
		}

		TwoClassicModelsCommand(Runnable afterDecisionModels) {
			this.afterDecisionModels = afterDecisionModels;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			firstModel = new CountingDecisionModel();
			secondModel = new SecondCountingDecisionModel();
			var result = context.decisionModels(firstModel, secondModel);

			// Simulate concurrent modification: inject event after reading state
			afterDecisionModels.run();

			result.raiseEvent(
					new FirstDomainEvent("dual-" + firstModel.count() + "-" + secondModel.count()),
					Tags.none()
			);
		}

		public CountingDecisionModel firstModel() { return firstModel; }
		public SecondCountingDecisionModel secondModel() { return secondModel; }
	}

	/**
	 * Command with mixed decision models: one classic (no initQuery) + one savepoint (with initQuery).
	 */
	static class MultiModelCommand implements Command<MockDomainEvent> {

		private CountingDecisionModel countingModel;
		private SavepointDecisionModel savepointModel;
		private final Runnable afterDecisionModels;

		MultiModelCommand() {
			this(() -> {});
		}

		MultiModelCommand(Runnable afterDecisionModels) {
			this.afterDecisionModels = afterDecisionModels;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			countingModel = new CountingDecisionModel();
			savepointModel = new SavepointDecisionModel();
			var result = context.decisionModels(countingModel, savepointModel);

			// Simulate concurrent modification: inject event after reading state
			afterDecisionModels.run();

			result.raiseEvent(
					new FirstDomainEvent("multi-" + countingModel.count() + "-" + savepointModel.count()),
					Tags.none()
			);
		}

		public CountingDecisionModel countingModel() { return countingModel; }
		public SavepointDecisionModel savepointModel() { return savepointModel; }
	}

	/**
	 * Command with no decision models.
	 */
	static class NoDecisionModelCommand implements Command<MockDomainEvent> {

		private final Runnable afterNoDecisionModels;

		NoDecisionModelCommand() {
			this(() -> {});
		}

		NoDecisionModelCommand(Runnable afterNoDecisionModels) {
			this.afterNoDecisionModels = afterNoDecisionModels;
		}

		@Override
		public void execute(
				CommandContext<MockDomainEvent, MockDomainEvent> context) {
			var result = context.noDecisionModels();
			afterNoDecisionModels.run();
			result.raiseEvent(new FirstDomainEvent("no-dm"), Tags.none());
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Decision model state population
	// ════════════════════════════════════════════════════════════════════

	@ForEachBackend
	void singleDecisionModelWithoutInitQuery_populatesState() {
		Mock domain = buildDomain();

		domain.event(new FirstDomainEvent("existing-1"));
		domain.event(new FirstDomainEvent("existing-2"));

		var cmd = new SingleModelCommand(10);
		domain.execute(cmd);

		assertEquals(2, cmd.model().count());
	}

	@ForEachBackend
	void singleDecisionModelWithoutInitQuery_sequentialCommandsSeeAllEvents() {
		Mock domain = buildDomain();

		domain.execute(new SingleModelCommand(10));
		domain.execute(new SingleModelCommand(10));

		var cmd = new SingleModelCommand(10);
		domain.execute(cmd);
		assertEquals(2, cmd.model().count());
	}

	@ForEachBackend
	void singleDecisionModelWithInitQuery_savepointSkipsOldEvents() {
		Mock domain = buildDomain();

		domain.event(new SecondDomainEvent("old-1"));
		domain.event(new SecondDomainEvent("old-2"));
		domain.event(new SecondDomainEvent("old-3"));
		domain.event(new ThirdDomainEvent("3"));
		domain.event(new SecondDomainEvent("new-1"));
		domain.event(new SecondDomainEvent("new-2"));

		var cmd = new SavepointModelCommand();
		domain.execute(cmd);

		// savepoint(3) + 2 new movements = 5
		assertEquals(5, cmd.model().count());
	}

	@ForEachBackend
	void singleDecisionModelWithInitQuery_noSavepoint() {
		Mock domain = buildDomain();

		domain.event(new SecondDomainEvent("movement-1"));
		domain.event(new SecondDomainEvent("movement-2"));

		var cmd = new SavepointModelCommand();
		domain.execute(cmd);

		assertEquals(2, cmd.model().count());
	}

	@ForEachBackend
	void singleDecisionModelWithInitQuery_emptyStream() {
		Mock domain = buildDomain();

		var cmd = new SavepointModelCommand();
		domain.execute(cmd);

		assertEquals(0, cmd.model().count());
	}

	@ForEachBackend
	void savepointModel_correctStateWithMultipleSavepoints() {
		Mock domain = buildDomain();

		domain.event(new ThirdDomainEvent("10"));
		domain.event(new SecondDomainEvent("m1"));
		domain.event(new SecondDomainEvent("m2"));
		domain.event(new ThirdDomainEvent("12"));
		domain.event(new SecondDomainEvent("m3"));

		var cmd = new SavepointModelCommand();
		domain.execute(cmd);

		// initQuery backwards limit(1) finds last ThirdDomainEvent("12")
		// eventQuery picks up 1 SecondDomainEvent after that
		// Total: 12 + 1 = 13
		assertEquals(13, cmd.model().count());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Multiple decision models — state population
	// ════════════════════════════════════════════════════════════════════

	@ForEachBackend
	void twoClassicModels_eachSeesOwnEventTypes() {
		Mock domain = buildDomain();

		domain.event(new FirstDomainEvent("f1"));
		domain.event(new SecondDomainEvent("s1"));
		domain.event(new FirstDomainEvent("f2"));
		domain.event(new SecondDomainEvent("s2"));
		domain.event(new SecondDomainEvent("s3"));

		var cmd = new TwoClassicModelsCommand();
		domain.execute(cmd);

		assertEquals(2, cmd.firstModel().count());
		assertEquals(3, cmd.secondModel().count());
	}

	@ForEachBackend
	void mixedModels_bothPopulatedCorrectly() {
		Mock domain = buildDomain();

		domain.event(new FirstDomainEvent("f1"));
		domain.event(new SecondDomainEvent("old-movement"));
		domain.event(new ThirdDomainEvent("1"));
		domain.event(new SecondDomainEvent("new-movement-1"));
		domain.event(new FirstDomainEvent("f2"));
		domain.event(new SecondDomainEvent("new-movement-2"));

		var cmd = new MultiModelCommand();
		domain.execute(cmd);

		// CountingDecisionModel: all FirstDomainEvents = 2
		assertEquals(2, cmd.countingModel().count());
		// SavepointDecisionModel: savepoint(1) + 2 new SecondDomainEvents = 3
		assertEquals(3, cmd.savepointModel().count());
	}

	@ForEachBackend
	void mixedModels_correctStateAfterMultipleCommands() {
		Mock domain = buildDomain();

		domain.event(new ThirdDomainEvent("0"));

		for (int i = 0; i < 3; i++) {
			domain.execute(new MultiModelCommand());
		}

		var cmd = new MultiModelCommand();
		domain.execute(cmd);

		// 3 FirstDomainEvents from previous commands
		assertEquals(3, cmd.countingModel().count());
		// savepoint at 0, no SecondDomainEvents
		assertEquals(0, cmd.savepointModel().count());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Optimistic locking — concurrent modification simulation
	// ════════════════════════════════════════════════════════════════════

	// In DCB, optimistic locking triggers when events matching the combined
	// eventQuery filter appear between the projector read and the final append.
	// We simulate this by injecting events during command execution via a Runnable.

	@ForEachBackend
	void optimisticLocking_singleModelNoInitQuery_conflictOnMatchingEventType() {
		Mock domain = buildDomain();
		domain.event(new FirstDomainEvent("baseline"));

		// Command injects a FirstDomainEvent after reading decision models
		assertOptimisticLockingException(() ->
				domain.execute(new SingleModelCommand(10) {
					@Override
					public void execute(
							CommandContext<MockDomainEvent, MockDomainEvent> context) {
						var m = new CountingDecisionModel();
						var result = context.decisionModels(m);
						// Simulate concurrent modification
						appendDirectly(new FirstDomainEvent("concurrent-event"));
						result.raiseEvent(new FirstDomainEvent("my-event"), Tags.none());
					}
				})
		);
	}

	@ForEachBackend
	void optimisticLocking_singleModelNoInitQuery_noConflictOnNonMatchingEventType() {
		Mock domain = buildDomain();
		domain.event(new FirstDomainEvent("baseline"));

		// Inject SecondDomainEvent — not in CountingDecisionModel's eventQuery filter
		var cmd = new SingleModelCommand(10) {
			@Override
			public void execute(
					CommandContext<MockDomainEvent, MockDomainEvent> context) {
				model = new CountingDecisionModel();
				var result = context.decisionModels(model);
				appendDirectly(new SecondDomainEvent("unrelated"));
				result.raiseEvent(new FirstDomainEvent("my-event"), Tags.none());
			}
		};
		domain.execute(cmd);
		assertEquals(1, cmd.model.count());
	}

	@ForEachBackend
	void optimisticLocking_singleModelWithInitQuery_conflictOnMovementEventType() {
		Mock domain = buildDomain();
		domain.event(new ThirdDomainEvent("0"));
		domain.event(new SecondDomainEvent("existing"));

		// Inject a SecondDomainEvent (movement type in eventQuery) during execution
		assertOptimisticLockingException(() ->
				domain.execute(new SavepointModelCommand() {
					@Override
					public void execute(
							CommandContext<MockDomainEvent, MockDomainEvent> context) {
						model = new SavepointDecisionModel();
						var result = context.decisionModels(model);
						appendDirectly(new SecondDomainEvent("concurrent-movement"));
						result.raiseEvent(new SecondDomainEvent("my-movement"), Tags.none());
					}
				})
		);
	}

	@ForEachBackend
	void optimisticLocking_singleModelWithInitQuery_noConflictOnSavepointEventType() {
		Mock domain = buildDomain();
		domain.event(new ThirdDomainEvent("0"));
		domain.event(new SecondDomainEvent("existing"));

		// Inject a ThirdDomainEvent (savepoint type in initQuery, NOT in eventQuery)
		var cmd = new SavepointModelCommand() {
			@Override
			public void execute(
					CommandContext<MockDomainEvent, MockDomainEvent> context) {
				model = new SavepointDecisionModel();
				var result = context.decisionModels(model);
				appendDirectly(new ThirdDomainEvent("99"));
				result.raiseEvent(new SecondDomainEvent("my-movement"), Tags.none());
			}
		};
		// Should succeed — ThirdDomainEvent is only in initQuery, not in optimistic lock filter
		domain.execute(cmd);
	}

	@ForEachBackend
	void optimisticLocking_twoClassicModels_conflictOnFirstModelEventType() {
		Mock domain = buildDomain();

		// Inject a FirstDomainEvent after reading both models
		assertOptimisticLockingException(() ->
				domain.execute(new TwoClassicModelsCommand(
						() -> appendDirectly(new FirstDomainEvent("concurrent"))
				))
		);
	}

	@ForEachBackend
	void optimisticLocking_twoClassicModels_conflictOnSecondModelEventType() {
		Mock domain = buildDomain();

		// Inject a SecondDomainEvent — the other model's event type, still in combined filter
		assertOptimisticLockingException(() ->
				domain.execute(new TwoClassicModelsCommand(
						() -> appendDirectly(new SecondDomainEvent("concurrent"))
				))
		);
	}

	@ForEachBackend
	void optimisticLocking_twoClassicModels_noConflictOnUnrelatedEventType() {
		Mock domain = buildDomain();

		// Inject ThirdDomainEvent — not in either model's eventQuery
		domain.execute(new TwoClassicModelsCommand(
				() -> appendDirectly(new ThirdDomainEvent("unrelated"))
		));
	}

	@ForEachBackend
	void optimisticLocking_mixedModels_conflictOnFirstModelEventType() {
		Mock domain = buildDomain();

		assertOptimisticLockingException(() ->
				domain.execute(new MultiModelCommand(
						() -> appendDirectly(new FirstDomainEvent("concurrent"))
				))
		);
	}

	@ForEachBackend
	void optimisticLocking_mixedModels_conflictOnSecondModelEventType() {
		Mock domain = buildDomain();

		assertOptimisticLockingException(() ->
				domain.execute(new MultiModelCommand(
						() -> appendDirectly(new SecondDomainEvent("concurrent"))
				))
		);
	}

	/**
	 * Regression test for the multi-decision-model optimistic-locking soundness gap: a conflicting event
	 * that is appended BETWEEN the sequential reads of a multi-read command must still be detected.
	 * <p>
	 * The command uses a plain model (its eventQuery is a single merged read) plus a savepoint model
	 * (its own read because of its initQuery), so two physical reads are performed and the boundary is
	 * pinned. The plain model injects a FirstDomainEvent and then a SecondDomainEvent while it is being
	 * projected — i.e. after the boundary but before the savepoint model's read. Without a pinned
	 * boundary the savepoint model's read would advance the lock cursor past the injected
	 * FirstDomainEvent (its most-recent SecondDomainEvent is more recent), so the append check would
	 * miss it. With the boundary pinned up front and every read bounded to it, both injected events fall
	 * after the boundary and the conflict is caught.
	 */
	@ForEachBackend
	void optimisticLocking_multipleModels_conflictBetweenSequentialReads_isCaught() {
		Mock domain = buildDomain();
		domain.event(new FirstDomainEvent("f0"));

		Runnable injectBetweenReads = () -> {
			appendDirectly(new FirstDomainEvent("concurrent-first"));
			appendDirectly(new SecondDomainEvent("concurrent-second"));
		};

		assertOptimisticLockingException(() ->
				domain.execute(new Command<MockDomainEvent>() {
					@Override
					public void execute(CommandContext<MockDomainEvent, MockDomainEvent> context) {
						var plain = new InjectingFirstDecisionModel(injectBetweenReads);
						var savepoint = new SavepointDecisionModel();
						var result = context.decisionModels(plain, savepoint);
						result.raiseEvent(new FirstDomainEvent("my-event"), Tags.none());
					}
				})
		);
	}

	@ForEachBackend
	void optimisticLocking_mixedModels_noConflictOnSavepointEventType() {
		Mock domain = buildDomain();

		// ThirdDomainEvent is only in initQuery, not in any eventQuery
		var cmd = new MultiModelCommand(
				() -> appendDirectly(new ThirdDomainEvent("99"))
		);
		domain.execute(cmd);
	}

	@ForEachBackend
	void optimisticLocking_mixedModels_savepointModelSeesNewSavepointCorrectly() {
		Mock domain = buildDomain();

		domain.event(new ThirdDomainEvent("0"));

		// Execute once without conflict to establish state
		domain.execute(new MultiModelCommand());

		// Add a new savepoint — should NOT cause conflict
		domain.event(new ThirdDomainEvent("99"));

		// Execute again — savepoint model should pick up the new savepoint
		var cmd = new MultiModelCommand();
		domain.execute(cmd);

		// initQuery finds ThirdDomainEvent("99"), no SecondDomainEvents after it
		assertEquals(99, cmd.savepointModel().count());
		// CountingDecisionModel sees the 1 FirstDomainEvent from the first execute
		assertEquals(1, cmd.countingModel().count());
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: No decision models — no optimistic locking
	// ════════════════════════════════════════════════════════════════════

	@ForEachBackend
	void noDecisionModels_noOptimisticLockingEvenWithConcurrentEvents() {
		Mock domain = buildDomain();

		// Inject all event types during execution — none should cause conflict
		domain.execute(new NoDecisionModelCommand(() -> {
			appendDirectly(new FirstDomainEvent("concurrent-1"));
			appendDirectly(new SecondDomainEvent("concurrent-2"));
			appendDirectly(new ThirdDomainEvent("concurrent-3"));
		}));
	}

	// ════════════════════════════════════════════════════════════════════
	// TESTS: Sequential commands without interference
	// ════════════════════════════════════════════════════════════════════

	@ForEachBackend
	void sequentialCommands_noConflictsWhenNoExternalModification() {
		Mock domain = buildDomain();

		for (int i = 0; i < 5; i++) {
			var cmd = new SingleModelCommand(10);
			domain.execute(cmd);
			assertEquals(i, cmd.model().count());
		}
	}

	@ForEachBackend
	void sequentialMixedCommands_noConflictsWhenNoExternalModification() {
		Mock domain = buildDomain();
		domain.event(new ThirdDomainEvent("0"));

		for (int i = 0; i < 5; i++) {
			domain.execute(new MultiModelCommand());
		}

		var cmd = new MultiModelCommand();
		domain.execute(cmd);
		assertEquals(5, cmd.countingModel().count());
		assertEquals(0, cmd.savepointModel().count());
	}

	@ForEachBackend
	void sequentialSavepointCommands_noConflictsAndCorrectState() {
		Mock domain = buildDomain();
		domain.event(new ThirdDomainEvent("0"));

		for (int i = 0; i < 3; i++) {
			domain.execute(new SavepointModelCommand());
		}

		var cmd = new SavepointModelCommand();
		domain.execute(cmd);

		// savepoint(0) + 3 existing SecondDomainEvents = 3
		assertEquals(3, cmd.model().count());
	}
}
