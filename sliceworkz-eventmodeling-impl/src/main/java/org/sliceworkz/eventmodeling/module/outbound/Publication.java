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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;

/**
 * One domain event published: the publisher handed the event and a {@link PublisherContext}, and what it
 * published appended to the outbound stream. The one code path for it, run by a publisher's processor and
 * by the published {@code PublisherTest} harness alike, so a test exercises exactly the keys, tags and
 * duplicate handling a deployment runs.
 * <p>
 * Everything published for one domain event is appended together, keyed {@code <event id>/<n>} in the
 * order the publisher called {@link PublisherContext#publish}, and tagged with the event it was published
 * for ({@link Publisher#TAG_PUBLISHED_FROM}) and the flow it belongs to. The domain event is handed over
 * again whenever the processor did not get to bookmark past it — a crash between the append and the
 * bookmark, a failover overlap — and the keys make that harmless whatever the publisher maps it into the
 * second time: the same events are a retry the store swallows, and different ones mix stored keys with new
 * ones, which the store refuses with an {@link IdempotencyKeyConflictException}. Every key derives from the
 * one domain event, so that refusal can only mean the event was published before: it is answered
 * {@link Outcome.AlreadyPublished}, and the first publication stands. Everywhere else that exception
 * retires a processor; here it is the expected answer.
 */
public final class Publication {

	private static final Logger LOGGER = LoggerFactory.getLogger(Publication.class);

	/**
	 * Reads a live read model up to and including an event.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
	 */
	@FunctionalInterface
	public interface BoundedReader<DOMAIN_EVENT_TYPE> {
		<R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R read ( Class<R> readModelClass, EventReference until, Tracing tracing, Object... params );
	}

	private Publication ( ) {
	}

	/**
	 * Publishes one domain event.
	 *
	 * @param publisher the publisher
	 * @param publisherName the name it is reported under
	 * @param event the domain event, as stored
	 * @param head the head of the domain stream, pinned before the publication: what {@code readLatest} reads up to
	 * @param instance the instance publishing, stamped on the outbound events
	 * @param reader how a live read model is read up to an event
	 * @param outboundEventStream where the outbound events go
	 * @return what the publication answered
	 */
	public static <DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> Outcome.PublicationResult publish (
			Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher, String publisherName, Event<DOMAIN_EVENT_TYPE> event,
			EventReference head, Instance instance, BoundedReader<DOMAIN_EVENT_TYPE> reader, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream ) {

		// the publication continues the flow of the domain event it publishes: same correlation id, so
		// "everything of this flow" finds the outbound events too
		Tracing tracing = Tracing.actorAndChannel(publisherName, "publication").instance(instance);
		String correlationId = Tracing.readFrom(event).correlationId();
		if ( correlationId != null ) {
			tracing = tracing.correlationId(correlationId);
		}

		Context<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context = new Context<>(publisher, event, head, tracing, reader);
		publisher.publish(event, context);

		if ( context.published.isEmpty() ) {
			return new Outcome.Published(Map.of(), List.of());
		}

		Tag publishedFrom = Tag.of(Publisher.TAG_PUBLISHED_FROM, event.reference().id().value());
		String keyPrefix = event.reference().id().value() + "/";
		List<EphemeralEvent<? extends OUTBOUND_EVENT_TYPE>> toAppend = new ArrayList<>();
		Map<EventType,Integer> publishedPerType = new LinkedHashMap<>();
		for ( int i = 0; i < context.published.size(); i++ ) {
			OUTBOUND_EVENT_TYPE outbound = context.published.get(i);
			Tags tags = context.publishedTags.get(i).merge(Tags.of(publishedFrom));
			toAppend.add(tracing.storeOn(Event.of(outbound, tags)).withIdempotencyKey(keyPrefix + (i + 1)));
			publishedPerType.merge(EventType.of(outbound.getClass()), 1, Integer::sum);
		}

		try {
			List<EventReference> appended = outboundEventStream.append(AppendCriteria.none(), toAppend).stream().map(Event::reference).toList();
			return new Outcome.Published(publishedPerType, appended);
		} catch ( IdempotencyKeyConflictException publishedBefore ) {
			// some keys stored, some not: a mapping other than the first, of an event published before
			LOGGER.debug("publisher {} mapped {} into other events than it published the first time; the first publication stands",
					publisherName, event.reference());
			return new Outcome.AlreadyPublished();
		}
	}

	private static final class Context<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements PublisherContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

		private final Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher;
		private final Set<Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>>> declared;
		private final Event<DOMAIN_EVENT_TYPE> event;
		private final EventReference head;
		private final Tracing tracing;
		private final BoundedReader<DOMAIN_EVENT_TYPE> reader;
		private final List<OUTBOUND_EVENT_TYPE> published = new ArrayList<>();
		private final List<Tags> publishedTags = new ArrayList<>();

		Context ( Publisher<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> publisher, Event<DOMAIN_EVENT_TYPE> event, EventReference head, Tracing tracing, BoundedReader<DOMAIN_EVENT_TYPE> reader ) {
			this.publisher = publisher;
			Set<Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>>> reads = publisher.reads();
			this.declared = ( reads == null ) ? Set.of() : reads;
			this.event = event;
			this.head = head;
			this.tracing = tracing;
			this.reader = reader;
		}

		@Override
		public <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readAsOfEvent ( Class<R> readModelClass, Object... params ) {
			return read(event.reference(), readModelClass, params);
		}

		@Override
		public <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readAsOf ( EventReference until, Class<R> readModelClass, Object... params ) {
			if ( until == null ) {
				throw new IllegalArgumentException("readAsOf needs the event to read up to; readLatest reads everything there is");
			}
			if ( until.storedEventHappenedAfter(head) ) {
				throw new IllegalArgumentException("readAsOf(%s) lies past the head this publication is pinned at (%s)".formatted(until, head));
			}
			return read(until, readModelClass, params);
		}

		@Override
		public <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readLatest ( Class<R> readModelClass, Object... params ) {
			return read(head, readModelClass, params);
		}

		private <R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R read ( EventReference until, Class<R> readModelClass, Object... params ) {
			if ( !declared.contains(readModelClass) ) {
				throw new IllegalArgumentException("publisher %s reads %s without declaring it in reads(): declare it there, and register it .live() beside the publisher"
						.formatted(publisher.getClass().getName(), readModelClass.getName()));
			}
			return reader.read(readModelClass, until, tracing, params);
		}

		@Override
		public void publish ( OUTBOUND_EVENT_TYPE outbound, Tags tags ) {
			if ( outbound == null ) {
				throw new IllegalArgumentException("publisher %s published null".formatted(publisher.getClass().getName()));
			}
			published.add(outbound);
			publishedTags.add(tags == null ? Tags.none() : tags);
		}
	}

}
