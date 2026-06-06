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

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * A {@link BoundedContextListener} that appends every {@link BoundedContextEvent} to a supplied
 * event stream. This reproduces the framework's historical behaviour of persisting kernel events
 * to a dedicated (e.g. "observability") stream.
 * <p>
 * The supplied stream must be configured for {@link BoundedContextEvent} (and its serialization),
 * for example:
 * <pre>
 *   EventStream&lt;BoundedContextEvent&gt; observability = eventStore.getEventStream(
 *       EventStreamId.forContext("my-context").withPurpose("observability"), BoundedContextEvent.class);
 *   ...
 *   .listener(new StreamAppendingBoundedContextListener(observability))
 * </pre>
 */
public class StreamAppendingBoundedContextListener implements BoundedContextListener {

	private final EventStream<BoundedContextEvent> stream;

	public StreamAppendingBoundedContextListener ( EventStream<BoundedContextEvent> stream ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required");
		}
		this.stream = stream;
	}

	@Override
	public void on ( EphemeralEvent<BoundedContextEvent> event ) {
		stream.append(AppendCriteria.none(), event);
	}

}
