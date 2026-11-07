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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.SliceMember;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.wiring.WiringFeatureSlice;
import org.sliceworkz.eventmodeling.slices.Aspect;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EventName;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins {@link SliceMember#reads()}: the slice inventory names the event types each member that declares a
 * filter is fed, as stored, so a reader can wire a context's members together before any of them has run;
 * empty for a filter matching nothing, {@code null} where the types are not known.
 */
public class SliceMemberReadsTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "WiringTestBoundedContext";

	private Map<String, SliceMember> announcedMembers ( ) {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()))
				.features()
					.rootPackage(WiringFeatureSlice.class.getPackage())
					.done();
		buildBoundedContext(builder);
		return received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received))
				.enabledFeatures().stream()
				.filter(s -> s.name().equals("Wiring"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected the Wiring feature slice"))
				.members().stream()
				.collect(Collectors.toMap(SliceMember::name, Function.identity()));
	}

	@Test
	void eachMemberThatDeclaresAFilterIsAnnouncedWithTheTypesItReads ( ) {
		Map<String, SliceMember> members = announcedMembers();

		assertEquals(Set.of("SomeInboundEvent"), members.get("WiringTranslator").reads(), "the translator's inbound type");
		assertEquals(Set.of("FirstDomainEvent", "SecondDomainEvent"), members.get("WiringPublisher").reads(), "the domain events the publisher publishes from");
		assertEquals(Set.of("SomeOutboundEvent"), members.get("WiringDispatcher").reads(), "the outbound event the dispatcher sends");
		assertEquals(Set.of("FirstDomainEvent", "SecondDomainEvent", "ThirdDomainEvent"), members.get("WiringPolicy").reads(),
				"a sealed interface in the filter stands for every type under it");
		assertEquals(Set.of("ThirdDomainEvent"), members.get("WiringReadModel").reads(), "an eventually consistent read model's query");
	}

	@Test
	void aFilterMatchingNothingReadsNothingAndOneNamingNoTypeIsUnknown ( ) {
		Map<String, SliceMember> members = announcedMembers();

		assertEquals(Set.of(), members.get("NothingReadModel").reads(), "fed nothing is an empty set");
		assertNull(members.get("TaggedDispatcher").reads(), "a filter on tags alone could be fed any type: unknown, not empty");
	}

	@Test
	void theReadsSurviveAMonitoringStreamRoundTrip ( ) {
		// what the dashboard sees: the inventory written to a monitoring stream and read back
		EventStream<BoundedContextEvent> kernelStream = EventStore.on(eventStorage()).build()
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("kernel"), BoundedContextEvent.class);
		var builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(new StreamAppendingBoundedContextListener(kernelStream))
				.features()
					.rootPackage(WiringFeatureSlice.class.getPackage())
					.done();
		buildBoundedContext(builder);

		SliceMember publisher = kernelStream.query(EventFilter.matchAll()).stream()
				.map(org.sliceworkz.eventstore.events.Event::data)
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.flatMap(s -> s.enabledFeatures().stream())
				.flatMap(s -> s.members().stream())
				.filter(m -> m.name().equals("WiringPublisher"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected the publisher in the stored inventory"));
		assertEquals(Set.of("FirstDomainEvent", "SecondDomainEvent"), publisher.reads());
	}

	@Test
	void aMemberAnnouncedWithoutItsReadsReadsAsUnknown ( ) {
		// every constructor that predates the field, and so every event stored before it
		assertNull(new SliceMember("x", BoundedContextEvent.MemberKind.DISPATCHER, Aspect.AUTOMATION).reads());
		assertNull(new SliceMember("x", BoundedContextEvent.MemberKind.READ_MODEL, Aspect.QUERY, BoundedContextEvent.ReadModelProjection.LIVE, "inbound").reads());
	}

	@EventName("CustomerSignedUp")
	record CustomerRegistered ( String id ) { }

	@Test
	void theStoredNameIsReportedNotTheClassName ( ) {
		assertEquals(Set.of("CustomerSignedUp"), BoundedContextBuilderImpl.eventTypesRead(() -> EventFilter.forTypes(CustomerRegistered.class)));
	}

	@Test
	void aMatchAllFilterAndAFilterThatCannotBeBuiltAreUnknown ( ) {
		assertNull(BoundedContextBuilderImpl.eventTypesRead(EventFilter::matchAll));
		assertNull(BoundedContextBuilderImpl.eventTypesRead(() -> { throw new IllegalStateException("not configured yet"); }));
		assertNull(BoundedContextBuilderImpl.eventTypesRead(() -> null));
	}

	@Test
	void aFilterWithOneUntypedItemIsUnknownWhateverItsOtherItemsName ( ) {
		assertNull(BoundedContextBuilderImpl.eventTypesRead(() -> EventFilter.forTypes(CustomerRegistered.class).or(EventFilter.forTags("k", "v"))));
	}

}
