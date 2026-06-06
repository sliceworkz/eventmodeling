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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;

/**
 * A {@link BoundedContextListener} that logs every {@link BoundedContextEvent} via SLF4J on the
 * {@code BOUNDEDCONTEXT} logger at {@code INFO} level. This replaces the previous {@code PERFORMANCE}
 * logger and can be wired explicitly when log output of kernel events is desired:
 * <pre>
 *   .listener(new LoggingBoundedContextListener())
 * </pre>
 */
public class LoggingBoundedContextListener implements BoundedContextListener {

	public static final Logger LOGGER = LoggerFactory.getLogger("BOUNDEDCONTEXT");

	@Override
	public void on ( EphemeralEvent<BoundedContextEvent> event ) {
		LOGGER.info("log=boundedcontext type={} data={} tags={}",
				event.data().getClass().getSimpleName(), event.data(), event.tags());
	}

}
