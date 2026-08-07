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

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE>
implements CommandResult<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	// TODO add monitoring & debugging metadata? (models used, events seen, timings, correlation id, actor / acting user, channel, ...)

	enum IdempotencyKeyStrategy {
		NONE,
		REQUIRE_EXTERNAL,
		FORBID_EXTERNAL,
		FALLBACK,
		EXCLUSIVE,
		OVERRIDE
	}

	private final String boundedContext;
	private final EventStreamId targetStreamId;
	private final Tracing tracing;
	private final EventFilter eventFilter;
	private final EventReference lastEventReference;
	private final List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> events;

	private IdempotencyKeyStrategy idempotencyKeyStrategy = IdempotencyKeyStrategy.NONE;
	private String internalIdempotencyKey;

	public CommandResultImpl ( String boundedContext, EventStreamId targetStreamId, Tracing tracing, EventFilter eventFilter, EventReference lastEventReference, List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> events ) {
		this.boundedContext = boundedContext;
		this.targetStreamId = targetStreamId;
		this.tracing = tracing;
		this.eventFilter = eventFilter;
		this.lastEventReference = lastEventReference;
		this.events = events;
	}

	public CommandResultImpl ( String boundedContext, EventStreamId targetStreamId, Tracing tracing, EventFilter eventFilter, EventReference lastEventReference ) {
		this(boundedContext, targetStreamId, tracing, eventFilter, lastEventReference, new ArrayList<>());
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags, String idempotencyKey ) {
		events.add(tracing.storeOn(Event.of(event, tags).withIdempotencyKey(idempotencyKey)));
		return this;
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> raiseEvent ( PRODUCED_EVENT_TYPE event, Tags tags ) {
		return raiseEvent(event, tags, null);
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> requireIdempotencyKey ( ) {
		this.idempotencyKeyStrategy = IdempotencyKeyStrategy.REQUIRE_EXTERNAL;
		return this;
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> idempotencyKey ( String key ) {
		this.idempotencyKeyStrategy = IdempotencyKeyStrategy.OVERRIDE;
		this.internalIdempotencyKey = key;
		return this;
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> fallbackIdempotencyKey ( String key ) {
		this.idempotencyKeyStrategy = IdempotencyKeyStrategy.FALLBACK;
		this.internalIdempotencyKey = key;
		return this;
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> exclusiveIdempotencyKey ( String key ) {
		this.idempotencyKeyStrategy = IdempotencyKeyStrategy.EXCLUSIVE;
		this.internalIdempotencyKey = key;
		return this;
	}

	@Override
	public CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> forbidIdempotencyKey ( ) {
		this.idempotencyKeyStrategy = IdempotencyKeyStrategy.FORBID_EXTERNAL;
		return this;
	}

	/**
	 * Resolves the effective idempotency key based on the command's strategy and any externally provided key.
	 */
	public String resolveIdempotencyKey ( String externalKey ) {
		return switch ( idempotencyKeyStrategy ) {
			case NONE -> externalKey;
			case REQUIRE_EXTERNAL -> {
				if ( externalKey == null ) {
					throw new IllegalStateException("command requires an externally provided idempotency key");
				}
				yield externalKey;
			}
			case FORBID_EXTERNAL -> {
				if ( externalKey != null ) {
					throw new IllegalStateException("command does not accept an externally provided idempotency key");
				}
				yield null;
			}
			case FALLBACK -> externalKey != null ? externalKey : internalIdempotencyKey;
			case EXCLUSIVE -> {
				if ( externalKey != null ) {
					throw new IllegalStateException("command provides its own idempotency key and does not accept an external one");
				}
				yield internalIdempotencyKey;
			}
			case OVERRIDE -> internalIdempotencyKey;
		};
	}

	public void applyIdempotencyKey ( String idempotencyKey ) {
		if ( events.size() == 1 ) {
			EphemeralEvent<? extends PRODUCED_EVENT_TYPE> event = events.get(0);
			if ( event.idempotencyKey() == null ) {
				events.set(0, event.withIdempotencyKey(idempotencyKey));
			}
		} else if ( events.size() > 1 ) {
			throw new IllegalArgumentException("command-level idempotency key cannot be used with commands that raise multiple events");
		}
	}

	/**
	 * Rejects outbound events raised without an idempotency key. Called by the DCB module when the
	 * target is the outbound stream, after the command-level key (if any) has been applied to the
	 * events — so a key from any source (per event, {@code idempotencyKey(...)} on this result, or
	 * externally provided) satisfies it. The callers that execute an {@code OutboundCommand} are
	 * at-least-once, which makes an unkeyed outbound event a duplicate publication on the first
	 * retry; {@link #forbidIdempotencyKey()} is the deliberate opt-out for a command that publishes
	 * without de-duplication on purpose. Throws before anything is appended, so nothing is stored.
	 */
	public void requireIdempotencyKeysOnOutboundEvents ( String commandName ) {
		if ( idempotencyKeyStrategy == IdempotencyKeyStrategy.FORBID_EXTERNAL ) {
			return;
		}
		for ( EphemeralEvent<? extends PRODUCED_EVENT_TYPE> event : events ) {
			if ( event.idempotencyKey() == null ) {
				throw new IllegalStateException(
						("outbound command '%s' raised %s without an idempotency key: every caller executing an OutboundCommand"
						+ " is effectively at-least-once, so an unkeyed outbound event is a duplicate publication on the first retry."
						+ " Key the event from the work item it publishes for (raiseEvent(event, tags, key), CommandResult.idempotencyKey(...),"
						+ " or an externally provided key), or call forbidIdempotencyKey() to publish without de-duplication deliberately")
							.formatted(commandName, event.data().getClass().getSimpleName()));
			}
		}
	}

	public List<EphemeralEvent<? extends PRODUCED_EVENT_TYPE>> raisedEvents ( ) {
		return events;
	}

	public AppendCriteria appendCriteria ( ) {
		return AppendCriteria.of(eventFilter, lastEventReference);
	}

	public String boundedContext ( ) {
		return boundedContext;
	}

	public EventStreamId targetStreamId ( ) {
		return targetStreamId;
	}

	public EventFilter eventFilter ( ) {
		return eventFilter;
	}

	public EventReference lastEventReference ( ) {
		return lastEventReference;
	}

}
