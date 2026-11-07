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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

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
 * A command's consistency boundary is pinned at the domain stream's head <em>before</em> its decision
 * models are read: every read is bounded at that head, and the head is the expected reference of the
 * append. Two things follow, and this scenario holds every backend to both.
 * <p>
 * <b>Soundness.</b> A command with several physical reads -- a savepoint model beside a plain one --
 * reads them one after the other, and nothing makes the group atomic. Without one boundary shared by
 * all of them, an event matching the first model can land between the reads and be walked past by
 * the lock reference the second read establishes, so the append is admitted against facts the command
 * never saw. With the boundary pinned first, everything after it is caught, and an <em>absent</em> head
 * -- an empty stream -- stays an absent reference, which the check reads as "I decided on an empty
 * boundary" and defends just the same.
 * <p>
 * <b>Cost.</b> The reference in the criteria decides what the store's check walks: on PostgreSQL the
 * probe walks every stream event after the reference, and the framework's stream is the whole bounded
 * context, so a reference at a quiet entity's own newest event walks everything the context appended
 * since. The head walks only what landed during the command, whatever the entity.
 * <p>
 * The outcome of every append is unchanged by the pin: the read is bounded at the head, so no matching
 * event sits between the model's newest event and the head, and "nothing matching after the head" is
 * the same verdict as "nothing matching after the newest event read". The characterisation scenarios
 * below say so; the pinning scenarios say where the reference lands.
 */
public class CommandBoundaryPinningTest extends AbstractMockDomainTest {

	private static final EventType FIRST = EventType.of(FirstDomainEvent.class);
	private static final EventType SECOND = EventType.of(SecondDomainEvent.class);
	private static final EventType THIRD = EventType.of(ThirdDomainEvent.class);

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

	private EventReference append ( MockDomainEvent event ) {
		return directStream.append(AppendCriteria.none(), Event.of(event, Tags.none())).getFirst().reference();
	}

	// --- decision models ---------------------------------------------------------------------------

	static class Firsts implements DecisionModel<MockDomainEvent> {
		int seen;
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(FirstDomainEvent.class); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	/** A savepoint model: its own physical read, so a command pairing it with a plain model reads twice. */
	static class SecondsSinceSavepoint implements DecisionModel<MockDomainEvent> {
		int seen;
		@Override public EventQuery initQuery ( ) { return EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class)).backwards().limit(1); }
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(SecondDomainEvent.class); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	// --- commands ----------------------------------------------------------------------------------

	/** Decides on Firsts, lets {@code between} run after the read, then raises a Third. */
	private static Command<MockDomainEvent> decideOnFirsts ( Runnable between ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.decisionModels(new Firsts());
				between.run();
				result.raiseEvent(new ThirdDomainEvent("decided"), Tags.none());
			}
		};
	}

	/** Two physical reads: the plain model and the savepoint model. */
	private static Command<MockDomainEvent> decideOnFirstsAndSeconds ( ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				context.decisionModels(new Firsts(), new SecondsSinceSavepoint())
						.raiseEvent(new ThirdDomainEvent("decided"), Tags.none());
			}
		};
	}

	private static Command<MockDomainEvent> decideOnNothing ( ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				context.noDecisionModels().raiseEvent(new ThirdDomainEvent("decided"), Tags.none());
			}
		};
	}

	// --- characterisation: what must not change ---------------------------------------------------

	@ForEachBackend
	void aMatchingEventAfterTheReadConflictsWhateverSitsAtTheHead ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));
		append(new SecondDomainEvent("s0")); // the head is an event the model's filter does not match

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOnFirsts(() -> append(new FirstDomainEvent("concurrent")))));
	}

	@ForEachBackend
	void anUnrelatedEventAfterTheReadDoesNotConflict ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));
		append(new SecondDomainEvent("s0"));

		domain.execute(decideOnFirsts(() -> append(new SecondDomainEvent("noise"))));
	}

	@ForEachBackend
	void anEmptyStreamIsAnEmptyBoundary ( ) {
		Mock domain = buildDomain();

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOnFirsts(() -> append(new FirstDomainEvent("concurrent")))),
				"a matching event landing on an empty boundary is a new relevant fact");

		domain.execute(decideOnFirsts(() -> append(new SecondDomainEvent("noise"))));
	}

	@ForEachBackend
	void aCommandWithoutDecisionModelsPinsNothingAndAppendsUnconditionally ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));

		domain.execute(decideOnNothing());

		assertEquals(0, countingStorage.headsDone(), "nothing to bound, nothing to pin");
		assertTrue(countingStorage.lastAppendCriteria().isNone());
	}

	// --- the pin ----------------------------------------------------------------------------------

	/**
	 * The soundness gap a shared boundary closes, on the one shape that has no boundary to pin from
	 * the models' own reads: an empty stream. Two physical reads -- the savepoint model's savepoint, then
	 * the merged read of both models; between them a Third lands (a savepoint the first read did not
	 * see), followed by a First and a Second the second read does. Without a pinned boundary the second
	 * read would establish a reference past all three, and the check would walk past the savepoint the
	 * command never saw. An absent head must stay absent -- the empty-boundary check then catches it.
	 */
	@ForEachBackend
	void aConflictBetweenTwoReadsOnAnEmptyBoundaryIsCaught ( ) {
		Mock domain = buildDomain();

		countingStorage.afterQuery(CommandBoundaryPinningTest::isTheSavepointRead, () -> {
			append(new ThirdDomainEvent("between-the-reads"));
			append(new FirstDomainEvent("seen-by-the-second-read"));
			append(new SecondDomainEvent("seen-by-the-second-read"));
		});

		assertThrows(OptimisticLockingException.class, () -> domain.execute(decideOnFirstsAndSeconds()),
				"a savepoint landed after the savepoint read and before the merged read");
	}

	@ForEachBackend
	void theCriteriaReferenceIsTheStreamHeadNotTheModelsNewestEvent ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));
		EventReference head = append(new SecondDomainEvent("s0"));

		domain.execute(decideOnFirsts(() -> { }));

		AppendCriteria criteria = countingStorage.lastAppendCriteria();
		assertNotNull(criteria);
		assertEquals(Optional.of(head), criteria.expectedLastEventReference(),
				"the expected reference is the stream head taken before the read, whatever the model matched");
		assertTrue(criteria.eventFilter().matches(FIRST, Tags.none(), head), "the lock filter is still the model's");
		assertFalse(criteria.eventFilter().matches(SECOND, Tags.none(), head));
	}

	@ForEachBackend
	void anEmptyStreamPinsAnAbsentReference ( ) {
		Mock domain = buildDomain();

		domain.execute(decideOnFirsts(() -> { }));

		AppendCriteria criteria = countingStorage.lastAppendCriteria();
		assertTrue(criteria.expectedLastEventReference().isEmpty(), "an absent head is an empty boundary");
		assertFalse(criteria.isNone(), "an empty boundary is still a boundary");
	}

	@ForEachBackend
	void everyDecisionModelReadIsBoundedAtTheHead ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));
		append(new ThirdDomainEvent("savepoint"));
		EventReference head = append(new SecondDomainEvent("s0"));

		domain.execute(decideOnFirstsAndSeconds());

		List<InvocationCountingEventStorage.Read> reads = countingStorage.readsSeen().stream().filter(r -> !r.filter().isMatchAll()).toList();
		assertTrue(reads.size() >= 2, "expected the savepoint read and the merged read of the models, got " + reads);
		for ( InvocationCountingEventStorage.Read read : reads ) {
			assertEquals(head, read.notAfter(), "a decision model read must be bounded at the pinned head: " + read);
		}
	}

	@ForEachBackend
	void theHeadIsTakenOncePerCommandBeforeAnyRead ( ) {
		Mock domain = buildDomain();
		append(new FirstDomainEvent("f0"));

		domain.execute(decideOnFirsts(() -> { }));
		assertEquals(1, countingStorage.headsDone(), "one pin for a single-read command");

		domain.execute(decideOnFirstsAndSeconds());
		assertEquals(2, countingStorage.headsDone(), "one pin for a multi-read command, shared by its reads");
	}

	// --- helpers ------------------------------------------------------------------------------------

	/** The plain model's read: matches Firsts and nothing else (a match-all pin would match both). */
	private static boolean isTheSavepointRead ( EventFilter query ) {
		EventReference any = EventReference.create(1, 1);
		return !query.isMatchAll() && query.matches(THIRD, Tags.none(), any) && !query.matches(FIRST, Tags.none(), any);
	}

}
