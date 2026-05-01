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
package org.sliceworkz.eventmodeling.events;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public record Tracing ( Instance instance, String actor, String channel, String command ) {

	public static final String UNKNOWN_ACTOR = null;
	public static final String UNKNOWN_CHANNEL = null;
	public static final String UNKNOWN_CHANNEL_LABEL = "unknown";

	public static final String AUTOMATION_ACTOR = "automation";
	public static final String AUTOMATION_CHANNEL = "automation";

	public static final String SYSTEM_ACTOR = "system";
	public static final String SYSTEM_CHANNEL = null;

	public static final String NO_COMMAND = null;


	private static final String TAG_INSTANCE_LOGICAL = "x-instance-logical";
	private static final String TAG_INSTANCE_PHYSICAL = "x-instance-physical";
	private static final String TAG_INSTANCE_PROCESS = "x-instance-process";
	private static final String TAG_CHANNEL = "x-channel";
	private static final String TAG_ACTOR = "x-actor";
	private static final String TAG_COMMAND = "x-command";

	public Tracing actor ( String actor ) {
		return new Tracing ( instance, actor, channel, command );
	}

	public Tracing channel ( String channel ) {
		return new Tracing ( instance, actor, channel, command );
	}

	public Tracing command ( String command ) {
		return new Tracing ( instance, actor, channel, command );
	}

	public static final Tracing init ( Instance instance ) {
		return new Tracing(instance, UNKNOWN_ACTOR, UNKNOWN_CHANNEL, NO_COMMAND);
	}

	public Tracing instance ( Instance instance ) {
		return new Tracing(instance, actor, channel, command);
	}

	public static final Tracing actorAndChannel ( String actor, String channel ) {
		return new Tracing ( null, actor, channel, NO_COMMAND);
	}

	public static final Tracing automation ( Instance instance ) {
		return new Tracing(instance, AUTOMATION_ACTOR, AUTOMATION_CHANNEL, NO_COMMAND);
	}

	public static final Tracing kernel ( Instance instance ) {
		return new Tracing(instance, SYSTEM_ACTOR, SYSTEM_CHANNEL, NO_COMMAND);
	}

	public static final <T> Event<T> removeFrom ( Event<T> event ) {
		Set<Tag> cleaned = new HashSet<>(event.tags().tags());
		cleaned.removeIf(t->t.key().startsWith("x-"));
		return event.withTags(new Tags(cleaned));
	}

	public static final Tracing readFrom ( Event<?> event ) {
		Instance instance = new Instance(
				tagValue(event, TAG_INSTANCE_LOGICAL).orElse(null),
				tagValue(event, TAG_INSTANCE_PHYSICAL).orElse(null),
				tagValue(event, TAG_INSTANCE_PROCESS).orElse(null)
				);
		String actor = tagValue(event, TAG_ACTOR).orElse(null);
		String channel = tagValue(event, TAG_CHANNEL).orElse(null);
		String command = tagValue(event, TAG_COMMAND).orElse(null);

		return new Tracing(instance, actor, channel, command);
	}

	private static final Optional<String> tagValue ( Event<?> event, String tagName ) {
		return event.tags().tag(tagName).map(Tag::value);
	}

	private static final void addTag ( Set<Tag> tags, String name, String value) {
		if ( value != null ) {
			tags.add(Tag.of(name, value));
		}
	}

	@SuppressWarnings("unchecked")
	public final <T> EphemeralEvent<T> storeOn ( EphemeralEvent<? extends T> event ) {
		Set<Tag> tags = new HashSet<>();
		if ( instance != null) {
			addTag(tags, TAG_INSTANCE_LOGICAL, instance.logical());
			addTag(tags, TAG_INSTANCE_PHYSICAL, instance.physical());
			addTag(tags, TAG_INSTANCE_PROCESS, instance.process());
		}
		addTag(tags, TAG_CHANNEL, channel);
		addTag(tags, TAG_ACTOR, actor);
		addTag(tags, TAG_COMMAND, command);

		Tags extraTags = new Tags(tags);
		Tags mergedTags = event.tags().merge(extraTags);

		return (EphemeralEvent<T>) event.withTags(mergedTags);
	}

}
