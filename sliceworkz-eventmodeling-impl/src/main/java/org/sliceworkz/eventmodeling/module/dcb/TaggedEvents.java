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

import java.util.Optional;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.Tagged;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The event store's check of a {@link Tagged} event's tags, run where a command raises the event.
 * <p>
 * The store merges an event's stated tags into the tags it was given and refuses an append whose tags do
 * not meet the event's requirements; that check is the one that decides. Running the same check when
 * the command raises the event makes the failure point at the line that raised it, inside the command,
 * rather than at the append the kernel makes afterwards. The tags the kernel adds later (rule tags) all
 * start with {@code x-}, which no requirement may name, so checking before they are added judges the same
 * thing the store judges.
 */
final class TaggedEvents {

	private TaggedEvents ( ) { }

	/**
	 * @throws EventSerializationException if the event's {@code eventTags()} throws or returns null
	 * @throws IllegalArgumentException if its tags, merged with the stated ones, do not meet its requirements
	 */
	static void checkRaised ( EphemeralEvent<?> event ) {
		if ( !(event.data() instanceof Tagged) ) {
			return;
		}
		Tags stated;
		try {
			stated = Tagged.tagsStatedBy(event.data());
		} catch ( RuntimeException e ) {
			throw new EventSerializationException(event.type(),
					"%s could not state its tags: %s".formatted(event.type().name(), e.getMessage()), e);
		}
		Optional<String> unmet = Tagged.unmetTagRequirements(event.data(), event.tags().merge(stated));
		if ( unmet.isPresent() ) {
			throw new IllegalArgumentException("%s is raised without the tags it requires: %s".formatted(event.type().name(), unmet.get()));
		}
	}

}
