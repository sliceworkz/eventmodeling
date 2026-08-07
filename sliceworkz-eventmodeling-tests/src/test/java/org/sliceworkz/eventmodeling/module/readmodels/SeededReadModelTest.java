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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventmodeling.readmodels.SeededReadModel;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A live model is always current and pays the whole event history for it; an eventually consistent
 * read model is already materialised and lags whatever its projector has not reached yet. A seeded
 * read model is the two combined: it takes the materialised state as a base and is projected only
 * over the events that have not reached that base — so the answer is as current as a live model's at
 * the cost of the outstanding delta.
 *
 * <p>The property that matters is that <b>the answer does not depend on how far the projector got</b>.
 * That is what these tests hold it to, from both ends: the same read is asked with a base that is
 * behind, with a base that is up to date, and with no base at all.
 */
public class SeededReadModelTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "UnitTestBoundedContext";
	private static final String DOMAIN_PURPOSE = "domain";

	/**
	 * The end-to-end shape: the base is an ordinary eventually consistent read model, projected in the
	 * background on its own thread, and the read catches it up.
	 *
	 * <p>Deliberately makes no claim about how far that projector had got when the read ran — it is a
	 * live thread and the events appended below may or may not have reached it. That is exactly the
	 * point: both outcomes have to give the same answer, and an assertion that pinned the projector's
	 * progress would be asserting the race rather than the property.
	 */
	@ForEachBackend
	void aSeededReadIsAsCurrentAsAFullProjection ( ) {
		appendEvents(10);

		Counts base = new Counts();
		var builder = builder();
		builder.readmodel(base).eventuallyConsistent();
		builder.readmodel(FreshCounts.class).live();
		builder.readmodel(FullCounts.class).live();
		Mock domain = buildBoundedContext(builder);   // start() waits for the ephemeral base to be projected

		appendEvents(5);

		FreshCounts fresh = domain.read(FreshCounts.class, base);
		FullCounts full = domain.read(FullCounts.class);

		assertEquals(15, fresh.count(), "a seeded read reflects everything in the stream, base or not");
		assertEquals(full.count(), fresh.count(), "which is what a live model projecting the lot would say");
	}

	/**
	 * The same read with the base held still, so the delta is exactly known: 10 events in the base, 5
	 * appended after it, and the read must fold those 5 and no others.
	 *
	 * <p>Also pins what the read costs. Streaming 15 events here would mean the seed was ignored and
	 * the read is a live model wearing a different name — which is a performance failure that produces
	 * entirely correct answers, and so is invisible without a count.
	 */
	@ForEachBackend
	void aSeededReadProjectsOnlyWhatTheBaseIsMissing ( ) {
		appendEvents(10);

		var builder = builder();
		builder.readmodel(FreshCounts.class).live();
		Mock domain = buildBoundedContext(builder);

		Counts base = projectedByHand();   // at 10, and it stays there: nothing is projecting it
		assertEquals(10, base.state().intValue());

		appendEvents(5);

		FreshCounts fresh = domain.read(FreshCounts.class, base);

		assertEquals(15, fresh.count());
		assertEquals(5, fresh.foldedInRead(), "only the events the base was missing were projected");
	}

	/**
	 * A base that reports no position is a base with nothing in it, and the read has to replay the
	 * stream — the alternative is answering from a model that holds none of it.
	 *
	 * <p>Which is why {@code seed()} reporting empty for a base that <em>does</em> exist is the
	 * expensive mistake: it is correct, so nothing fails, and it costs the whole history per read.
	 */
	@ForEachBackend
	void aBaseThatHasProjectedNothingIsReplayedInFull ( ) {
		appendEvents(10);

		var builder = builder();
		builder.readmodel(FreshCounts.class).live();
		Mock domain = buildBoundedContext(builder);

		FreshCounts fresh = domain.read(FreshCounts.class, new Counts());

		assertEquals(10, fresh.count());
		assertEquals(10, fresh.foldedInRead(), "nothing to start from, so everything is projected");
	}

	/**
	 * {@code seededAt} on {@link BoundedContextEvent.LiveModelProjected} is what makes the mistake
	 * above visible: a seeded read model reporting no base looks exactly like an ordinary live model
	 * from the outside, and the only symptom is a read that is slow for no stated reason.
	 */
	@Test
	void aSeededReadReportsThePositionItStartedFrom ( ) {
		appendEvents(10);

		List<BoundedContextEvent> observed = new CopyOnWriteArrayList<>();
		var builder = builder().listener(( EphemeralEvent<BoundedContextEvent> e ) -> observed.add(e.data()));
		builder.readmodel(FreshCounts.class).live();
		builder.readmodel(FullCounts.class).live();
		Mock domain = buildBoundedContext(builder);

		Counts base = projectedByHand();
		observed.clear();

		domain.read(FreshCounts.class, base);
		domain.read(FullCounts.class);

		assertEquals(base.upTo(), projectedEvent(observed, "FreshCounts").seededAt(),
				"a seeded read says where it started, so a seed that silently returns nothing is visible");
		assertNull(projectedEvent(observed, "FullCounts").seededAt(),
				"an ordinary live model has no base and says so");
	}

	// -- registration --

	/**
	 * A seed says where a <em>read</em> resumes projecting, and only the live path ever asks for one.
	 * Registered as eventually consistent, the processor would resume from its bookmark and
	 * {@code seed()} would never be called — leaving a read model that looks seeded and is not.
	 */
	@Test
	void aSeededReadModelCannotBeRegisteredAsEventuallyConsistent ( ) {
		var builder = builder();

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> builder.readmodel(new FreshCounts(new Counts())).eventuallyConsistent());

		assertTrue(thrown.getMessage().contains("live()"), "and the message says how to register it instead: " + thrown.getMessage());
	}

	/**
	 * A snapshot and a seed are two complete answers to the same question — where does this projection
	 * start — with no sensible precedence between them, so configuring both is a mistake rather than a
	 * preference.
	 */
	@Test
	void aSeededReadModelCannotAlsoBeRegisteredWithSnapshots ( ) {
		var builder = builder();

		assertThrows(IllegalArgumentException.class,
				() -> builder.readmodel(FreshCounts.class).snapshots(new SnapshotStorage<Integer>() {

					@Override
					public Optional<SnapshotRecord<Integer>> load ( String key, String version ) {
						return Optional.empty();
					}

					@Override
					public void save ( String key, String version, Integer snapshot, EventReference eventReference ) {
					}

				}));
	}

	// -- helpers --

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private BoundedContextEvent.LiveModelProjected projectedEvent ( List<BoundedContextEvent> observed, String readModel ) {
		return observed.stream()
				.filter(BoundedContextEvent.LiveModelProjected.class::isInstance)
				.map(BoundedContextEvent.LiveModelProjected.class::cast)
				.filter(e -> readModel.equals(e.readModel()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no LiveModelProjected for " + readModel + " in " + observed));
	}

	/**
	 * A base projected on this thread and then left alone, so the delta a read has to fold is exactly
	 * what was appended afterwards rather than whatever a background thread happened to reach.
	 */
	private Counts projectedByHand ( ) {
		Counts base = new Counts();
		Projector.from(domainStream()).towards(base).build().run();
		return base;
	}

	private void appendEvents ( int count ) {
		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>();
		for ( int i = 0; i < count; i++ ) {
			events.add(Event.of(new FirstDomainEvent("event " + i), Tags.none()));
		}
		domainStream().append(AppendCriteria.none(), events);
	}

	private EventStream<MockDomainEvent> domainStream ( ) {
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage());
		return eventStore.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose(DOMAIN_PURPOSE), MockDomainEvent.class);
	}

}

/**
 * The base: an ordinary eventually consistent read model, except that it folds into an immutable
 * state and publishes that state with the position it reflects — which is what a seeded read needs
 * and what a read model mutating its own fields cannot offer.
 */
class Counts extends PublishingReadModel<MockDomainEvent,Integer> {

	@Override
	protected Integer initialState ( ) {
		return 0;
	}

	@Override
	protected Integer apply ( Integer state, Event<MockDomainEvent> event ) {
		return state + 1;
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

}

/**
 * The read: takes the base as it stands and folds whatever has not reached it yet, with the same
 * {@code apply} the base itself uses — the rule is written once and used from both sides.
 */
class FreshCounts implements SeededReadModel<MockDomainEvent> {

	private final Counts base;
	private int count;
	private int foldedInRead;

	FreshCounts ( Counts base ) {
		this.base = base;
	}

	@Override
	public Optional<EventReference> seed ( ) {
		// one volatile read, so the state and the position it reflects cannot disagree
		ReadModelResult<Integer> published = base.published();
		count = published.data();
		return Optional.ofNullable(published.upTo());
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

	@Override
	public void when ( Event<MockDomainEvent> event ) {
		count++;
		foldedInRead++;
	}

	int count ( ) {
		return count;
	}

	/** How many events this read had to project on top of its base. */
	int foldedInRead ( ) {
		return foldedInRead;
	}

}

/** An ordinary live model: the same answer the expensive way, as the thing to be equal to. */
class FullCounts implements ReadModel<MockDomainEvent> {

	private int count;

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

	@Override
	public void when ( MockDomainEvent event ) {
		count++;
	}

	int count ( ) {
		return count;
	}

}
