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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A command reads each savepoint model's savepoint on its own, and then the savepoint models' forward
 * reads in one: each is its filter bounded after its own savepoint, and differently bounded items unite.
 * The plain models keep a read of their own, unbounded, as before. Each model is handed exactly what its
 * own read would have returned, and the lock filter keeps what each model decided on.
 */
public class SavepointModelsShareOneReadTest extends AbstractMockDomainTest {

	private InvocationCountingEventStorage countingStorage;
	private EventStream<MockDomainEvent> directStream;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		this.countingStorage = new InvocationCountingEventStorage(eventStorage());
		this.directStream = EventStore.on(eventStorage()).build()
				.getEventStream(BoundedContextStreams.domain("UnitTestBoundedContext"), MockDomainEvent.class);
	}

	private Mock buildDomain ( ) {
		return buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(countingStorage)
				.instance(InstanceFactory.determine("unittests")));
	}

	private EventReference append ( MockDomainEvent event, String entity ) {
		return directStream.append(AppendCriteria.none(), Event.of(event, Tags.of("entity", entity))).getFirst().reference();
	}

	/** The newest Third of an entity, then that entity's Firsts and Seconds after it. */
	static class SinceSavepoint implements DecisionModel<MockDomainEvent> {
		final String entity;
		final List<String> seen = new ArrayList<>();
		SinceSavepoint ( String entity ) { this.entity = entity; }
		@Override public EventQuery initQuery ( ) { return EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class).tagged("entity", entity)).backwards().limit(1); }
		@Override public EventQuery eventQuery ( ) { return EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class, SecondDomainEvent.class).tagged("entity", entity)); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen.add(value(event)); }
	}

	/** Every First, whatever its entity. */
	static class AllFirsts implements DecisionModel<MockDomainEvent> {
		final List<String> seen = new ArrayList<>();
		@Override public EventQuery eventQuery ( ) { return EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class)); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen.add(value(event)); }
	}

	/** The Seconds recorded up to a point: a business bound on what is relevant. */
	static class SecondsUntil implements DecisionModel<MockDomainEvent> {
		final EventReference until;
		final List<String> seen = new ArrayList<>();
		SecondsUntil ( EventReference until ) { this.until = until; }
		@Override public EventQuery eventQuery ( ) { return EventQuery.of(EventFilter.forTypes(SecondDomainEvent.class).until(until)); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen.add(value(event)); }
	}

	private static Command<MockDomainEvent> decideOn ( List<DecisionModel<MockDomainEvent>> models, Runnable between ) {
		return new Command<>() {
			@Override
			@SuppressWarnings("unchecked")
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.decisionModels(models.toArray(new DecisionModel[0]));
				between.run();
				result.raiseEvent(new ThirdDomainEvent("decided"), Tags.of("entity", "decided"));
			}
		};
	}

	private static String value ( Event<MockDomainEvent> event ) {
		return switch ( event.data() ) {
			case FirstDomainEvent f -> f.value();
			case SecondDomainEvent s -> s.value();
			case ThirdDomainEvent t -> t.value();
		};
	}

	@ForEachBackend
	void eachSavepointIsReadOnItsOwnAndTheSavepointModelsForwardReadsAreOne ( ) {
		append(new FirstDomainEvent("a1"), "a");
		append(new ThirdDomainEvent("a-count"), "a");
		append(new FirstDomainEvent("a2"), "a");
		append(new SecondDomainEvent("b1"), "b");
		append(new ThirdDomainEvent("b-count"), "b");
		append(new SecondDomainEvent("b2"), "b");
		Mock domain = buildDomain();

		SinceSavepoint a = new SinceSavepoint("a");
		SinceSavepoint b = new SinceSavepoint("b");
		AllFirsts firsts = new AllFirsts();
		int before = countingStorage.queriesDone();
		domain.execute(decideOn(List.of(a, b, firsts), () -> { }));

		assertEquals(4, countingStorage.queriesDone() - before,
				"two savepoint reads, one read for both savepoint models, one for the plain model: " + countingStorage.readsSeen());
		assertTrue(countingStorage.readsSeen().stream().anyMatch(read -> !read.filter().isMatchAll() && read.filter().items().size() == 2
				&& read.filter().items().stream().allMatch(item -> item.after() != null)),
				"the savepoint models' reads are one, each bounded after its own savepoint: " + countingStorage.readsSeen());
		assertEquals(List.of("a-count", "a2"), a.seen, "a's savepoint, then only what followed it");
		assertEquals(List.of("b-count", "b2"), b.seen, "b's savepoint, then only what followed it");
		assertEquals(List.of("a1", "a2"), firsts.seen, "the plain model reads everything it names");
	}

	@ForEachBackend
	void aModelWithoutASavepointReadsItsWholeHistoryInTheMergedRead ( ) {
		append(new FirstDomainEvent("a1"), "a");
		append(new SecondDomainEvent("a2"), "a");
		Mock domain = buildDomain();

		SinceSavepoint a = new SinceSavepoint("a");
		domain.execute(decideOn(List.of(a, new AllFirsts()), () -> { }));

		assertEquals(List.of("a1", "a2"), a.seen);
	}

	@ForEachBackend
	void aSavepointLandingAfterTheReadsStillConflicts ( ) {
		append(new ThirdDomainEvent("a-count"), "a");
		append(new FirstDomainEvent("a1"), "a");
		Mock domain = buildDomain();

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOn(List.of(new SinceSavepoint("a"), new AllFirsts()), () -> append(new ThirdDomainEvent("a-recount"), "a"))),
				"the savepoint the model decided on is no longer the newest");
	}

	@ForEachBackend
	void aMovementAfterTheSavepointLandingAfterTheReadsConflicts ( ) {
		append(new ThirdDomainEvent("a-count"), "a");
		Mock domain = buildDomain();

		assertThrows(OptimisticLockingException.class,
				() -> domain.execute(decideOn(List.of(new SinceSavepoint("a"), new AllFirsts()), () -> append(new SecondDomainEvent("a-moved"), "a"))));
	}

	@ForEachBackend
	void anotherEntitysEventsDoNotConflict ( ) {
		append(new ThirdDomainEvent("a-count"), "a");
		Mock domain = buildDomain();

		domain.execute(decideOn(List.of(new SinceSavepoint("a")), () -> {
			append(new ThirdDomainEvent("b-count"), "b");
			append(new SecondDomainEvent("b-moved"), "b");
		}));
	}

	@ForEachBackend
	void aModelBoundedUntilAnEarlierPointIsLeftOutOfTheCheckAndSeesOnlyItsRange ( ) {
		append(new SecondDomainEvent("s1"), "x");
		EventReference closed = append(new SecondDomainEvent("s2"), "x");
		append(new SecondDomainEvent("s3"), "x");
		Mock domain = buildDomain();

		SecondsUntil secondsUntil = new SecondsUntil(closed);
		domain.execute(decideOn(List.of(secondsUntil, new SinceSavepoint("a")), () -> append(new SecondDomainEvent("late"), "x")));

		assertEquals(List.of("s1", "s2"), secondsUntil.seen, "only what was recorded up to its bound");
		AppendCriteria criteria = countingStorage.lastAppendCriteria();
		assertFalse(criteria.eventFilter().items().stream().anyMatch(item -> item.until() != null),
				"an item bounded before the head can match nothing appended later, so it is left out: " + criteria.eventFilter());
		assertTrue(criteria.eventFilter().items().stream().noneMatch(item -> item.after() != null),
				"a savepoint model's lower bound lies before the head and is dropped: " + criteria.eventFilter());
	}
}
