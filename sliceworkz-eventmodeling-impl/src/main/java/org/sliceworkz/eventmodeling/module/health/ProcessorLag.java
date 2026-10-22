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
package org.sliceworkz.eventmodeling.module.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessorAdmin.LagTarget;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * How long the oldest event each processor has not read yet has been waiting.
 * <p>
 * A processor is behind when its stream's head is past its bookmark's read position — what it has read,
 * relevant to it or not. How far behind is the age of the first stored event after that position, on this
 * instance's clock: that is how long the processor has left an event unread, which is what tells a
 * processor stuck for an hour from one that simply has not run since an event arrived a moment ago. The
 * alternative — counting the events past the bookmark, as the dashboard does — loses for a probe asked
 * every few seconds: a count is a read capped at some length, and its size says nothing about time, which
 * is what a tolerance is set in.
 * <p>
 * Everything goes through the storage SPI, raw: a head and a page of one stored event, never deserialized,
 * upcast or decrypted, so a poison event or an unreachable key store is not a lag. One head per stream and
 * one bookmark per processor, plus one read for a processor that is behind.
 */
final class ProcessorLag {

	private final EventStorage storage;
	private final Map<ProcessorKind, EventStreamId> streams;

	ProcessorLag ( EventStorage storage, Map<ProcessorKind, EventStreamId> streams ) {
		this.storage = storage;
		this.streams = Map.copyOf(streams);
	}

	/**
	 * The processors among the targets that are behind, with how long their oldest unread event has waited.
	 *
	 * @param targets the processors to measure
	 * @param now the moment to measure against
	 * @return the processors that are behind, caught up ones left out, in the order given
	 */
	List<Behind> measure ( List<LagTarget> targets, Instant now ) {
		Map<EventStreamId, Optional<EventReference>> heads = new HashMap<>();
		List<Behind> behind = new ArrayList<>();
		for ( LagTarget target : targets ) {
			EventStreamId stream = streams.get(target.kind());
			if ( stream == null ) {
				continue;
			}
			Optional<EventReference> head = heads.computeIfAbsent(stream, storage::head);
			if ( head.isEmpty() ) {
				continue; // an empty stream leaves nothing unread
			}
			EventReference readUpTo = storage.findBookmark(target.reader()).map(Bookmark::readUpToOrReference).orElse(null);
			if ( readUpTo != null && !head.get().storedEventHappenedAfter(readUpTo) ) {
				continue;
			}
			Optional<StoredEvent> oldestUnread = storage.query(EventFilter.matchAll(), stream, readUpTo, Limit.to(1), Direction.FORWARD).stream().findFirst();
			oldestUnread.ifPresent(unread -> behind.add(new Behind(target, Duration.between(unread.timestamp(), now).isNegative()
					? Duration.ZERO
					: Duration.between(unread.timestamp(), now))));
		}
		return behind;
	}

	/**
	 * A processor that has not read its whole stream.
	 *
	 * @param target the processor
	 * @param oldestUnreadWaited how long the oldest event it has not read has been stored
	 */
	record Behind ( LagTarget target, Duration oldestUnreadWaited ) { }

}
