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
package org.sliceworkz.eventmodeling.boundedcontext;

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Where a bounded context keeps its events in the store: one stream per kind of event, under the
 * context's {@linkplain BoundedContextBuilder#name(String) name}.
 * <p>
 * This layout is wire format. The streams are what sits in the store, so every reader outside the
 * context that runs on them has to find them the same way the context wrote them: an operator's
 * dashboard, a benchmark tracking its own progress, a test asserting on what a command appended.
 * None of those holds a built context — tooling usually runs in another process — so the layout is
 * stated here, as a function of the context's name, and the context builds its own streams from it.
 * Changing a purpose here changes where every new event goes and none of the old ones, exactly like
 * renaming the context.
 * <p>
 * <strong>Not for application code.</strong> An application reaches its events through what the
 * framework puts in front of them: commands and aggregates to decide, read models to read, a todo
 * list and an automation to react, a translator for what comes in, a dispatcher for what goes out.
 * Each of those carries a bookmark, a lease, retry and the lifecycle events that say it is working.
 * A reader opening one of these streams itself gives all of that up — a subscription on it misses
 * whatever is appended while the process is down, runs on every instance, and is not retried when it
 * throws.
 * <p>
 * The alternative — the purposes as private constants of the implementation — loses because nothing
 * then checks the copies every out-of-context reader has to make: a copy compiles for as long as it
 * happens to agree with the implementation, and reads an empty stream, silently, the moment it does
 * not. {@link org.sliceworkz.eventmodeling.management.ManagementInstruction#STREAM} is the same idea
 * for the stream an operator instructs the deployment through.
 */
public final class BoundedContextStreams {

	/** The purpose of the stream a context appends its domain events to. */
	public static final String DOMAIN = "domain";

	/** The purpose of the stream a context receives inbound events on. */
	public static final String INBOUND = "inbound";

	/** The purpose of the stream a context publishes outbound events on. */
	public static final String OUTBOUND = "outbound";

	private BoundedContextStreams ( ) {
	}

	/**
	 * @param contextName the bounded context's name
	 * @return the stream that context appends its domain events to
	 */
	public static EventStreamId domain ( String contextName ) {
		return EventStreamId.forContext(contextName).withPurpose(DOMAIN);
	}

	/**
	 * @param contextName the bounded context's name
	 * @return the stream that context receives inbound events on
	 */
	public static EventStreamId inbound ( String contextName ) {
		return EventStreamId.forContext(contextName).withPurpose(INBOUND);
	}

	/**
	 * @param contextName the bounded context's name
	 * @return the stream that context publishes outbound events on
	 */
	public static EventStreamId outbound ( String contextName ) {
		return EventStreamId.forContext(contextName).withPurpose(OUTBOUND);
	}

}
