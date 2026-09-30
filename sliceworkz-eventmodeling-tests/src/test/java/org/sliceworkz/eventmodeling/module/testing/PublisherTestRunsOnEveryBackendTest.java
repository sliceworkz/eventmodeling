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
package org.sliceworkz.eventmodeling.module.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.mock.publishing.ItemPublisher;
import org.sliceworkz.eventmodeling.mock.publishing.ItemReadModel;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.testing.PublisherTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * What a publisher's reads see and what a re-publication does, through the <em>published</em>
 * {@link PublisherTest} base — and that the base runs against every registered event storage. The
 * storage-sensitive halves are the bounded reads (an {@code until} compared on the backend's own order) and
 * the keys (the store's batch rule deciding between a swallowed retry and a mapping published before).
 */
public class PublisherTestRunsOnEveryBackendTest extends PublisherTest<MockDomainEvent, MockInboundEvent, MockOutboundEvent> {

	private Publisher<MockDomainEvent, MockOutboundEvent> publisher = new ItemPublisher();

	@Override
	public Publisher<MockDomainEvent, MockOutboundEvent> publisher ( ) {
		return publisher;
	}

	@Override
	public Class<MockDomainEvent> domainEventType ( ) {
		return MockDomainEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@ForEachBackend
	void aReadAsOfTheEventSeesTheStateThatEventLeftBehind ( ) {
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
			.and(new SecondDomainEvent("v2"), ItemReadModel.tags("a"))
			.and(new FirstDomainEvent("v3"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1"), new SomeOutboundEvent("a:v1,v2,v3"));
	}

	@ForEachBackend
	void aReadOfTheLatestSeesEverythingThereIs ( ) {
		publisher = new ItemPublisher(true);
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
			.and(new SecondDomainEvent("v2"), ItemReadModel.tags("a"))
			.and(new FirstDomainEvent("v3"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1,v2,v3"), new SomeOutboundEvent("a:v1,v2,v3"));
	}

	@ForEachBackend
	void aSecondRoundPublishesOnlyWhatIsNew ( ) {
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1"))
		.and()
			.and(new FirstDomainEvent("v2"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1,v2"));
	}

	@ForEachBackend
	void anOutboundEventNamesTheDomainEventItWasPublishedFor ( ) {
		var result = given(new FirstDomainEvent("v1"), ItemReadModel.tags("a")).whenPublished();
		Event<MockOutboundEvent> published = result.outboundEvents().get(0);
		Event<MockDomainEvent> source = domainStream().query(EventQuery.matchAll()).get(0);
		assertEquals(source.reference().id().value(), published.tags().tag(Publisher.TAG_PUBLISHED_FROM).orElseThrow().value());
		assertTrue(published.tags().containsAll(ItemReadModel.tags("a")));
	}

	@ForEachBackend
	void aRedeliveredEventMappedTheSameWayIsARetryTheStoreSwallows ( ) {
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1"))
		.and()
		.whenRepublished()
			.nothingPublished();
	}

	@ForEachBackend
	void aRedeliveredEventMappedAnotherWayLeavesTheFirstPublicationStanding ( ) {
		publisher = new OnePerValuePublisher();
		var definition = given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"));
		definition.whenPublished().published(new SomeOutboundEvent("v1"));

		// the state moves on, and the event is handed over again: the latest state now maps it into two
		// events, the first under the key already stored and the second under a new one
		var republished = definition.and(new SecondDomainEvent("v2"), ItemReadModel.tags("a")).whenRepublished();

		republished.nothingPublished();
		assertEquals(List.of(new Outcome.AlreadyPublished()), republished.outcomes());
	}

	@ForEachBackend
	void aPublisherReadingAReadModelItDidNotDeclareFails ( ) {
		publisher = new UndeclaredReadPublisher();
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
		.whenPublished()
			.failed(IllegalArgumentException.class);
	}

	@Test
	void aPlainTestStillRunsAgainstTheInMemoryStore ( ) {
		given(new FirstDomainEvent("v1"), ItemReadModel.tags("a"))
		.whenPublished()
			.published(new SomeOutboundEvent("a:v1"));
	}

	/** Publishes one event per value of the item's latest state: a publisher whose mapping moves with the state. */
	static class OnePerValuePublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forTypes(FirstDomainEvent.class);
		}

		@Override
		public Set<Class<? extends ReadModel<? extends MockDomainEvent>>> reads ( ) {
			return Set.of(ItemReadModel.class);
		}

		@Override
		public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) {
			String itemId = event.tags().tag(ItemReadModel.ITEM).orElseThrow().value();
			for ( String value : context.readLatest(ItemReadModel.class, itemId).state().split(",") ) {
				context.publish(new SomeOutboundEvent(value), Tags.none());
			}
		}
	}

	/** Reads a read model without declaring it. */
	static class UndeclaredReadPublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forTypes(FirstDomainEvent.class);
		}

		@Override
		public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) {
			context.readAsOfEvent(ItemReadModel.class, "a");
		}
	}

}
