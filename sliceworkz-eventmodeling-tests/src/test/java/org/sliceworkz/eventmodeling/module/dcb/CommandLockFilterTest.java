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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * What a command locks on: the union of <em>every</em> filter its decision models were read with, item
 * bounds included. {@link CommandBoundaryPinningTest} says where the criteria's
 * reference lands; this scenario says which events that reference is compared against.
 * <p>
 * <b>A savepoint model decides on two reads, and both are facts.</b> Its {@code initQuery} answers
 * "the newest savepoint is X" and its {@code eventQuery} "these are the movements after it" -- the
 * first as load-bearing as the second, since a command stamps what the savepoint told it (the active
 * period, the carry-forward balance) into the events it raises. Locked on the {@code eventQuery}
 * alone, an event of a savepoint type landing after the boundary matches nothing in the criteria and
 * the append is admitted, so that stale answer is written after the event that changed it with
 * nothing raised. The hole is systematic rather than occasional: the savepoint pattern asks for the
 * two queries to name disjoint event types, so the types most able to invalidate the decision are
 * exactly the ones the {@code eventQuery} does not name.
 * <p>
 * <b>A model's own bounds reach the criteria, and are pruned against the head.</b> An item bounded
 * {@code until} a point before the head can match nothing appended later and is removed; an
 * {@code after} before the head is dropped. So the criteria carry no bound, and the model's
 * <em>read</em> keeps the one it asked for.
 */
public class CommandLockFilterTest extends AbstractMockDomainTest {

	private static final EventType FIRST = EventType.of(FirstDomainEvent.class);
	private static final EventType SECOND = EventType.of(SecondDomainEvent.class);
	private static final EventType THIRD = EventType.of(ThirdDomainEvent.class);

	private static final Tags ACCOUNT = Tags.of("account", "a1");
	private static final Tags OTHER_ACCOUNT = Tags.of("account", "a2");

	private InvocationCountingEventStorage countingStorage;
	private EventStream<MockDomainEvent> directStream;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		this.countingStorage = new InvocationCountingEventStorage(eventStorage());
		// bypasses the counting storage on purpose, so seeding and injecting count as nothing
		this.directStream = EventStore.on(eventStorage()).build()
				.getEventStream(BoundedContextStreams.domain("UnitTestBoundedContext"), MockDomainEvent.class);
	}

	private Mock buildDomain ( ) {
		return buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(countingStorage)
				.instance(InstanceFactory.determine("unittests")));
	}

	private EventReference append ( MockDomainEvent event, Tags tags ) {
		return directStream.append(AppendCriteria.none(), Event.of(event, tags)).getFirst().reference();
	}

	// --- decision models ---------------------------------------------------------------------------

	/** The savepoint shape: the newest Third initialises it, the Seconds after that are replayed. */
	static class SecondsSinceSavepoint implements DecisionModel<MockDomainEvent> {
		private final Tags tags;
		int seen;
		SecondsSinceSavepoint ( Tags tags ) { this.tags = tags; }
		@Override public EventQuery initQuery ( ) { return EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class).tagged(tags)).backwards().limit(1); }
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(SecondDomainEvent.class).tagged(tags); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	/** A model that bounds its own read. The bound is the read's, never the criteria's. */
	static class FirstsUntil implements DecisionModel<MockDomainEvent> {
		private final EventReference until;
		int seen;
		FirstsUntil ( EventReference until ) { this.until = until; }
		@Override public EventFilter eventFilter ( ) {
			return EventFilter.forTypes(FirstDomainEvent.class);
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.of(eventFilter()).notAfter(until);
		}
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	// --- commands ----------------------------------------------------------------------------------

	/** Decides on the savepoint model, lets {@code between} run after the reads, then raises a First. */
	private static Command<MockDomainEvent> decideOnSavepoint ( Tags tags, Runnable between ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.decisionModels(new SecondsSinceSavepoint(tags));
				between.run();
				result.raiseEvent(new FirstDomainEvent("decided"), tags);
			}
		};
	}

	private static Command<MockDomainEvent> decideOnFirstsUntil ( EventReference until, Runnable between ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.decisionModels(new FirstsUntil(until));
				between.run();
				result.raiseEvent(new SecondDomainEvent("decided"), Tags.none());
			}
		};
	}

	// --- the savepoint query is locked on ----------------------------------------------------------

	@ForEachBackend
	void aSavepointLandingAfterTheReadConflicts ( ) {
		Mock domain = buildDomain();
		append(new ThirdDomainEvent("savepoint"), ACCOUNT);
		append(new SecondDomainEvent("s0"), ACCOUNT);

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOnSavepoint(ACCOUNT, () -> append(new ThirdDomainEvent("newer savepoint"), ACCOUNT))),
				"the model decided the newest savepoint was the one it read; a newer one is a new relevant fact");
	}

	/**
	 * A model that found no savepoint replayed from the beginning, which makes a savepoint appearing
	 * after the boundary just as invalidating: the command still stamped an answer the savepoint moves.
	 */
	@ForEachBackend
	void aSavepointLandingAfterTheReadConflictsEvenWhenTheModelFoundNone ( ) {
		Mock domain = buildDomain();
		append(new SecondDomainEvent("s0"), ACCOUNT);

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOnSavepoint(ACCOUNT, () -> append(new ThirdDomainEvent("first savepoint"), ACCOUNT))),
				"an empty savepoint boundary is still a boundary");
	}

	/** The union widens the types, never the tags: another entity's savepoint is not our fact. */
	@ForEachBackend
	void aSavepointForAnotherEntityDoesNotConflict ( ) {
		Mock domain = buildDomain();
		append(new ThirdDomainEvent("savepoint"), ACCOUNT);

		domain.execute(decideOnSavepoint(ACCOUNT, () -> append(new ThirdDomainEvent("elsewhere"), OTHER_ACCOUNT)));
	}

	@ForEachBackend
	void theLockFilterCoversBothQueriesOfASavepointModel ( ) {
		Mock domain = buildDomain();
		EventReference head = append(new ThirdDomainEvent("savepoint"), ACCOUNT);

		domain.execute(decideOnSavepoint(ACCOUNT, () -> { }));

		AppendCriteria criteria = countingStorage.lastAppendCriteria();
		assertNotNull(criteria);
		assertTrue(criteria.eventFilter().matches(SECOND, ACCOUNT, head), "the eventQuery's types are locked on");
		assertTrue(criteria.eventFilter().matches(THIRD, ACCOUNT, head), "so are the initQuery's");
		assertFalse(criteria.eventFilter().matches(FIRST, ACCOUNT, head), "and nothing neither query named");
		assertFalse(criteria.eventFilter().matches(THIRD, OTHER_ACCOUNT, head), "each query keeps its own tags");
	}

	// --- a model's own until stays on the read ------------------------------------------------------

	@ForEachBackend
	void aModelsOwnUntilDoesNotReachTheCriteria ( ) {
		Mock domain = buildDomain();
		EventReference bound = append(new FirstDomainEvent("f0"), Tags.none());

		domain.execute(decideOnFirstsUntil(bound, () -> { }));

		AppendCriteria criteria = countingStorage.lastAppendCriteria();
		assertNotNull(criteria);
		assertFalse(criteria.eventFilter().isBounded(),
				"the model's read bound is a property of its read, not of what is relevant: " + criteria.eventFilter());
	}

	@ForEachBackend
	void aMatchingEventAfterTheBoundaryConflictsThoughTheModelBoundedItsOwnRead ( ) {
		Mock domain = buildDomain();
		EventReference bound = append(new FirstDomainEvent("f0"), Tags.none());

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOnFirstsUntil(bound, () -> append(new FirstDomainEvent("concurrent"), Tags.none()))),
				"the command's boundary is the pinned head, whatever bound the model put on its own read");
	}

}
