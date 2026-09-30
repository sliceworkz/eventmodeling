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

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * What a {@link Publisher} may do with the domain event it is handed: read live read models, and publish.
 * <p>
 * <strong>Every read is bounded, and the publisher says where.</strong> The data an outbound event carries
 * is either the state as it was when the fact was recorded, or the latest state there is — and which of the
 * two a consumer receives is a decision every consumer lives with, so there is no read that leaves it
 * unsaid:
 * <ul>
 * <li>{@link #readAsOfEvent} — the state as of the domain event being published, that event included and
 *     nothing after it. What a consumer should usually receive: the message describes the state the
 *     decision produced, and a burst of changes is published as the sequence of states it went through.</li>
 * <li>{@link #readAsOf} — the state as of an event you name: the domain event that recorded the decision,
 *     when that is not the event being published.</li>
 * <li>{@link #readLatest} — the state as it is now, when the consumer wants the newest data and only
 *     convergence matters. "Now" is pinned once per publication, at the head of the domain stream when
 *     {@link Publisher#publish} was called, so several reads in one publication see the same moment.</li>
 * </ul>
 * A bounded read cannot start from a base that lies past its boundary: a
 * {@link org.sliceworkz.eventmodeling.readmodels.SeededReadModel seed} or a snapshot that reflects later
 * events is ignored, and the read replays from the beginning — correct, and as expensive as the history is
 * long. So a read model a publisher reads as of an event is best kept to the size of a decision model:
 * one entity's events, narrowly tagged.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 * @param <OUTBOUND_EVENT_TYPE> the base type of the bounded context's outbound events
 */
public interface PublisherContext<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Reads a live read model as of the domain event being published: every event up to and including it,
	 * nothing after it.
	 *
	 * @param readModelClass a class the publisher {@linkplain Publisher#reads() declares}
	 * @param params the read model's constructor parameters
	 * @return the read model, projected up to the event being published
	 * @throws IllegalArgumentException when the publisher did not declare the class
	 */
	<R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readAsOfEvent ( Class<R> readModelClass, Object... params );

	/**
	 * Reads a live read model as of the event named: every event up to and including it, nothing after.
	 *
	 * @param until the last event the read includes; at or before the head the publication is pinned at
	 * @param readModelClass a class the publisher {@linkplain Publisher#reads() declares}
	 * @param params the read model's constructor parameters
	 * @return the read model, projected up to {@code until}
	 * @throws IllegalArgumentException when the publisher did not declare the class, or {@code until} is null
	 */
	<R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readAsOf ( EventReference until, Class<R> readModelClass, Object... params );

	/**
	 * Reads a live read model as it is now: every event up to the head of the domain stream, pinned once
	 * when the publication started.
	 *
	 * @param readModelClass a class the publisher {@linkplain Publisher#reads() declares}
	 * @param params the read model's constructor parameters
	 * @return the read model, projected up to the pinned head
	 * @throws IllegalArgumentException when the publisher did not declare the class
	 */
	<R extends ReadModel<? extends DOMAIN_EVENT_TYPE>> R readLatest ( Class<R> readModelClass, Object... params );

	/**
	 * Publishes an outbound event for the domain event being published. Nothing is appended until
	 * {@link Publisher#publish} returns; everything published for one domain event is then appended to
	 * the outbound stream together, keyed by the domain event's id and the order of these calls, and tagged
	 * with the domain event it was published for ({@code x-published-from}) and the flow it belongs to.
	 *
	 * @param event the outbound event
	 * @param tags the tags it carries, beside the ones the framework adds
	 */
	void publish ( OUTBOUND_EVENT_TYPE event, Tags tags );

}
