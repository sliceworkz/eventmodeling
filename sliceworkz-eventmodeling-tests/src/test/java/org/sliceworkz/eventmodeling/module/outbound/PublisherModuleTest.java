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
package org.sliceworkz.eventmodeling.module.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockPublisher;
import org.sliceworkz.eventmodeling.mock.misplacedpublisher.MisplacedPublisherFeatureSlice;
import org.sliceworkz.eventmodeling.mock.publishing.ItemPublisher;
import org.sliceworkz.eventmodeling.mock.publishing.ItemReadModel;
import org.sliceworkz.eventmodeling.mock.publishing.PublishingFeatureSlice;
import org.sliceworkz.eventmodeling.mock.querylivepublisher.QueryLiveFeatureSlice;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.SeededReadModel;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A publisher registered on a bounded context: what it appends and how, and what {@code build()} refuses
 * about how it was registered. The mapping itself — the read modes, the first publication standing — is
 * pinned through the published {@code PublisherTest} base in {@code PublisherTestRunsOnEveryBackendTest}.
 */
public class PublisherModuleTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "PublishingBoundedContext";

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private List<Event<MockOutboundEvent>> outbound ( ) {
		return eventStore().getEventStream(BoundedContextStreams.outbound(CONTEXT_NAME), MockOutboundEvent.class).query(EventQuery.matchAll());
	}

	// ── what a publication appends ───────────────────────────────────────────

	@ForEachBackend
	void aRecordedFactIsPublishedTaggedWithItsSourceAndItsFlow ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(PublishingFeatureSlice.class.getPackage()).done();
		Mock context = buildBoundedContext(builder);

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests")).actor("alice").correlationId("flow-1");
		Optional<EventReference> recorded = context.event(new FirstDomainEvent("v1"), ItemReadModel.tags("a"), tracing);

		waitBecauseOfEventualConsistency(() -> outbound().size() >= 1);
		Event<MockOutboundEvent> published = outbound().get(0);

		assertEquals(new SomeOutboundEvent("a:v1"), published.data());
		assertEquals(recorded.orElseThrow().id().value(), published.tags().tag(Publisher.TAG_PUBLISHED_FROM).orElseThrow().value(),
				"an outbound event names the domain event it was published for");
		assertEquals("flow-1", Tracing.readFrom(published).correlationId(), "and continues its flow");
		assertTrue(published.tags().containsAll(ItemReadModel.tags("a")), "and carries the tags the publisher gave it");
	}

	@Test
	void aPublisherIsAProcessorAnOperatorCanStopAndRestart ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.publisher(new MockPublisher());
		Mock context = buildBoundedContext(builder);

		ProcessorStatus status = context.processors().stream()
				.filter(p -> p.kind() == ProcessorKind.PUBLISHER).findFirst().orElseThrow();
		assertEquals("MockPublisher", status.name());

		assertTrue(context.stopProcessor(ProcessorKind.PUBLISHER, "MockPublisher"));
		context.event(new FirstDomainEvent("while-stopped"));
		assertTrue(context.restartProcessor(ProcessorKind.PUBLISHER, "MockPublisher"));

		waitBecauseOfEventualConsistency(() -> outbound().size() >= 1);
		assertEquals(new SomeOutboundEvent("while-stopped"), outbound().get(0).data(), "nothing is lost while a publisher is stopped");
	}

	/**
	 * A seeded read model loads its base as {@code seed()} answers where that base reflects, and a base past
	 * a read's boundary cannot be unprojected — so a bounded read never seeds, and replays instead. Seeded
	 * here, the published state would carry the seed's marker.
	 */
	@Test
	void aReadAsOfAnEventIsNeverSeeded ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(SeededItem.class).live();
		builder.publisher(new SeededItemPublisher());
		Mock context = buildBoundedContext(builder);

		context.event(new FirstDomainEvent("v1"), ItemReadModel.tags("a"));

		waitBecauseOfEventualConsistency(() -> outbound().size() >= 1);
		assertEquals(new SomeOutboundEvent("v1"), outbound().get(0).data());
	}

	// ── how a publisher has to be registered ─────────────────────────────────

	@Test
	void aPublisherRegisteredFromAnotherAspectThanAutomationIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(MisplacedPublisherFeatureSlice.class.getPackage()).done();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("publisher registered outside the automation aspect: MockPublisher (registered by slice MisplacedPublisher"
				+ " from configureProjection -- register it from configureAutomation)", e.getMessage());
	}

	@Test
	void aPublisherWhoseReadModelIsNotRegisteredLiveIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.publisher(new ItemPublisher());

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("publisher reads a read model that is not registered live on this instance: ItemPublisher reads ItemReadModel"
				+ " (register it with builder.readmodel(ItemReadModel.class).live() beside the publisher)", e.getMessage());
	}

	@Test
	void aReadModelRegisteredOnlyForQueriesIsMissingOnAnInstanceThatRunsAutomationsAlone ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(QueryLiveFeatureSlice.class.getPackage())
				.disableCommands().disableQueries().disableProjections().done();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(e.getMessage().contains("ItemPublisher reads ItemReadModel"), e.getMessage());
	}

	@Test
	void theSameReadModelRegisteredQueryAndAutomationSideIsOneRegistration ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(PublishingFeatureSlice.class.getPackage()).done();
		Mock context = buildBoundedContext(builder);

		assertEquals("v1", context.event(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
				.map(ref -> context.read(ItemReadModel.class, "a").state()).orElseThrow());
	}

	@Test
	void theSameReadModelRegisteredTwiceWithDifferentSnapshotSettingsIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(ItemReadModel.class).live();
		builder.readmodel(ItemReadModel.class).snapshots(new NoSnapshots()).readAndWrite();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(e.getMessage().contains("registered twice with different snapshot settings"), e.getMessage());
	}

	@Test
	void twoPublishersUnderOneNameAreRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.publisher(new MockPublisher());
		builder.publisher(new MockPublisher());

		assertThrows(IllegalArgumentException.class, builder::build);
	}

	@Test
	void anAnonymousPublisherIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.publisher(new MockPublisher() { });

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertFalse(e.getMessage().isBlank());
	}

	/** An item whose seed would mark its state, so a read that seeded it shows. */
	public static class SeededItem extends ItemReadModel implements SeededReadModel<MockDomainEvent> {

		private boolean seeded;

		public SeededItem ( String itemId ) {
			super(itemId);
		}

		@Override
		public Optional<EventReference> seed ( ) {
			seeded = true;
			return Optional.empty();
		}

		@Override
		public String state ( ) {
			return seeded ? "SEEDED," + super.state() : super.state();
		}
	}

	public static class SeededItemPublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forTypes(FirstDomainEvent.class);
		}

		@Override
		public Set<Class<? extends ReadModel<? extends MockDomainEvent>>> reads ( ) {
			return Set.of(SeededItem.class);
		}

		@Override
		public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) {
			context.publish(new SomeOutboundEvent(context.readAsOfEvent(SeededItem.class, "a").state()), Tags.none());
		}
	}

	static class NoSnapshots implements SnapshotStorage<Object> {
		@Override public Optional<SnapshotRecord<Object>> load ( String key, String version ) { return Optional.empty(); }
		@Override public void save ( String key, String version, Object snapshot, EventReference lastEventReference ) { }
	}

}
