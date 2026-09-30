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
package org.sliceworkz.eventmodeling.outbound;

import java.util.Set;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Maps the bounded context's domain events into its published language: the outbound events a
 * {@link Dispatcher} then sends to the outside world.
 * <p>
 * A command decides and records its conclusion as domain events, and a publisher tells the world about
 * that conclusion afterwards. It is the counterpart of a {@link org.sliceworkz.eventmodeling.inbound.Translator},
 * pointing the other way:
 * <pre>
 * command ─► domain stream ─► Publisher ─► outbound stream ─► Dispatcher ─► message bus, webhook, …
 * </pre>
 * Every outbound event is published by a publisher, so every one of them traces back to the domain event
 * it was published for.
 * <p>
 * <strong>How it runs.</strong> A publisher is projected over the domain stream on a single elected leader
 * of the deployment, and bookmarked there like a translator or a dispatcher. It belongs to the slice whose
 * domain event it publishes — a state change or an automation, which the event model shows with the
 * integration event linked to it — and is part of that slice's automation aspect: register it from
 * {@code configureAutomation} with {@code builder.publisher(...)}, together with the live read models it
 * {@linkplain #reads() reads}.
 * <p>
 * <strong>What it may do</strong> is read live read models and publish, through its
 * {@link PublisherContext} — nothing else. It decides nothing: whatever needs deciding is decided by a
 * command and recorded as a domain event first, which is what the publisher then maps. It writes no
 * domain events and calls out to nothing; sending is a dispatcher's job.
 * <p>
 * <strong>Publishing is effectively once per domain event.</strong> Each outbound event published for a
 * domain event is keyed by that event's id and its position among the events published for it
 * ({@code <event id>/1}, {@code <event id>/2}, …), and all of them are appended together. When the event
 * is handed over again — a crash between the append and the bookmark, a failover overlap — what was
 * published the first time stands: a mapping that publishes the same again is swallowed as a retry, and
 * one that publishes something else (a publisher reading the {@linkplain PublisherContext#readLatest latest}
 * state, which may have moved on) is recognised as published before and stores nothing.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of the bounded context's outbound events
 */
public interface Publisher<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * The tag every outbound event carries, naming the id of the domain event it was published for: the
	 * causation link, so "why did we publish this" and "what did we publish for that" are tag queries.
	 */
	String TAG_PUBLISHED_FROM = "x-published-from";

	/**
	 * The domain events this publisher publishes for.
	 *
	 * @return the query selecting them from the domain stream
	 */
	EventQuery eventQuery ( );

	/**
	 * The live read models {@link #publish} reads. Each must be registered {@code .live()} on every
	 * instance the publisher is deployed on — {@code build()} refuses the publisher otherwise, naming what
	 * is missing — and a read of a class not declared here is refused. Registered from
	 * {@code configureAutomation}, beside the publisher, since a read model registered only from
	 * {@code configureQuery} does not exist on an instance that runs automations alone.
	 *
	 * @return the read model classes; empty for a publisher that maps the event on its own
	 */
	default Set<Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>>> reads ( ) {
		return Set.of();
	}

	/**
	 * Publishes zero, one or several outbound events for one domain event, through
	 * {@link PublisherContext#publish}. Called on the publisher's processor thread, once per matching
	 * domain event, in stream order — and again for the same event after a crash or a failover, see above.
	 *
	 * @param event the domain event, as stored
	 * @param context what the publisher may do
	 */
	void publish ( Event<DOMAIN_EVENT_TYPE> event, PublisherContext<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> context );

}
