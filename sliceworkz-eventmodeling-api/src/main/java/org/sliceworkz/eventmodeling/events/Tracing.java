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
import java.util.UUID;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public record Tracing ( Instance instance, String actor, String channel, String command, String agentId, String agentName, String correlationId ) {

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
	private static final String TAG_AGENT_ID = "x-agent-id";
	private static final String TAG_AGENT_NAME = "x-agent-name";

	/**
	 * The tag carrying the correlation id. Public, unlike the other tag names: a consumer that wants
	 * every event of one flow queries for this tag by name (e.g.
	 * {@code EventQuery.forEvents(EventTypesFilter.any(), Tags.of(Tracing.TAG_CORRELATION_ID, id))}),
	 * where the other tracing tags only ever round-trip through {@link #storeOn} / {@link #readFrom}.
	 */
	public static final String TAG_CORRELATION_ID = "x-correlation-id";

	public Tracing actor ( String actor ) {
		return new Tracing ( instance, actor, channel, command, agentId, agentName, correlationId );
	}

	public Tracing channel ( String channel ) {
		return new Tracing ( instance, actor, channel, command, agentId, agentName, correlationId );
	}

	public Tracing command ( String command ) {
		return new Tracing ( instance, actor, channel, command, agentId, agentName, correlationId );
	}

	/**
	 * Returns a copy stamped with the acting agent's identity, for calls made through an agent key
	 * (MCP). Both values are optional; a {@code null} pair leaves no agent tags on the event.
	 */
	public Tracing agent ( String agentId, String agentName ) {
		return new Tracing ( instance, actor, channel, command, agentId, agentName, correlationId );
	}

	/**
	 * Returns a copy carrying the given correlation id, replacing the one this tracing was minted
	 * with. The correlation id names the <em>flow</em> an event belongs to and is meant to be reused,
	 * never re-minted, across every step of that flow: command → domain event → todo list → automation
	 * → outbound event, and across bounded contexts. A step that reacts to an event continues the flow
	 * by carrying that event's correlation id ({@link #readFrom}) onto everything it raises.
	 */
	public Tracing correlationId ( String correlationId ) {
		return new Tracing ( instance, actor, channel, command, agentId, agentName, correlationId );
	}

	public static final Tracing init ( Instance instance ) {
		return new Tracing(instance, UNKNOWN_ACTOR, UNKNOWN_CHANNEL, NO_COMMAND, null, null, mintCorrelationId());
	}

	public Tracing instance ( Instance instance ) {
		return new Tracing(instance, actor, channel, command, agentId, agentName, correlationId);
	}

	public static final Tracing actorAndChannel ( String actor, String channel ) {
		return new Tracing ( null, actor, channel, NO_COMMAND, null, null, mintCorrelationId());
	}

	public static final Tracing automation ( Instance instance ) {
		return new Tracing(instance, AUTOMATION_ACTOR, AUTOMATION_CHANNEL, NO_COMMAND, null, null, mintCorrelationId());
	}

	public static final Tracing kernel ( Instance instance ) {
		return new Tracing(instance, SYSTEM_ACTOR, SYSTEM_CHANNEL, NO_COMMAND, null, null, mintCorrelationId());
	}

	/**
	 * Every factory mints a correlation id, so a flow carries one from its very first event and no
	 * append path has to check for its absence. Minting happens <em>only</em> in the factories — never
	 * in the canonical constructor or in {@link #storeOn} — so copying a tracing field by field, or
	 * reading one back from a stored event, reproduces it exactly rather than silently starting a new
	 * flow, and all events of one append carry the same id.
	 */
	private static final String mintCorrelationId ( ) {
		return UUID.randomUUID().toString();
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
		String agentId = tagValue(event, TAG_AGENT_ID).orElse(null);
		String agentName = tagValue(event, TAG_AGENT_NAME).orElse(null);
		String correlationId = tagValue(event, TAG_CORRELATION_ID).orElse(null);

		return new Tracing(instance, actor, channel, command, agentId, agentName, correlationId);
	}

	private static final Optional<String> tagValue ( Event<?> event, String tagName ) {
		return event.tags().tag(tagName).map(Tag::value);
	}

	/**
	 * Adds a tracing tag, unless the value carries nothing to trace.
	 * <p>
	 * A tag value that is blank, or that has leading or trailing whitespace, is rejected by
	 * {@link Tag} because it does not survive the round trip through the stored form. Tracing is
	 * decoration the framework attaches on the caller's behalf, so rather than let a stray space in a
	 * channel name abort the command that raised the event, a blank value is dropped and the rest is
	 * stripped.
	 */
	private static final void addTag ( Set<Tag> tags, String name, String value) {
		if ( value != null && !value.isBlank() ) {
			tags.add(Tag.of(name, value.strip()));
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
		addTag(tags, TAG_AGENT_ID, agentId);
		addTag(tags, TAG_AGENT_NAME, agentName);
		addTag(tags, TAG_CORRELATION_ID, correlationId);

		Tags extraTags = new Tags(tags);
		Tags mergedTags = event.tags().merge(extraTags);

		return (EphemeralEvent<T>) event.withTags(mergedTags);
	}

}
