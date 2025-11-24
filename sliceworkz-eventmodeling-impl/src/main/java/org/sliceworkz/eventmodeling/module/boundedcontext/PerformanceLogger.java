/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent.Metrics;

public class PerformanceLogger {
	
	public static final Logger LOGGER = LoggerFactory.getLogger("PERFORMANCE");

	private Map<String,String> keyValues = new HashMap<>();

	public PerformanceLogger context ( String value ) {
		add("context", value);
		return this;
	}

	public PerformanceLogger type ( String value ) {
		add("type", value);
		return this;
	}

	public PerformanceLogger readmodel ( String value ) {
		add("readmodel", value);
		return this;
	}

	public PerformanceLogger command ( String value ) {
		add("command", value);
		return this;
	}

	public PerformanceLogger processor ( String value ) {
		add("processor", value);
		return this;
	}

	public PerformanceLogger instance ( Instance instance ) {
		add("instance.logical", instance.logical());
		add("instance.physical", instance.physical());
		add("instance.process", instance.process());
		return this;
	}

	public PerformanceLogger metrics ( Metrics metrics ) {
		add("duration", metrics.durationMs());
		add("queriesDone", metrics.queriesDone());
		add("eventsStreamed", metrics.eventStreamed());
		add("eventsHandled", metrics.eventsHandled());
		if ( metrics.until() != null ) {
			add("until.id", metrics.until().id().value());
			add("until.pos", metrics.until().position());
		}
		return this;
	}

	public String log ( ) {
		return log(null);
	}

	public String log ( String message ) {
		StringBuilder sb = new StringBuilder("log=performance "); // with space at the end to separate
		if ( message != null ) {
			sb.append(message);
			sb.append(" : ");
		}
		for ( var e: keyValues.entrySet() ) {
			sb.append(e.getKey());
			sb.append("=");
			sb.append(e.getValue());
			sb.append(" ");
		}
		String result = sb.toString();
		LOGGER.info(result);
		return result;
	}

	public static PerformanceLogger entry ( ) {
		return new PerformanceLogger();
	}

	private void add ( String key, String value ) {
		keyValues.put(key, value);
	}
	private void add ( String key, int value ) {
		keyValues.put(key, Integer.toString(value));
	}
	private void add ( String key, long value ) {
		keyValues.put(key, Long.toString(value));
	}
	
}
