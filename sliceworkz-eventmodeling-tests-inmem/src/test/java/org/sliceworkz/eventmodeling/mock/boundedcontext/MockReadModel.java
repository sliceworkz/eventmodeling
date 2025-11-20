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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import java.util.List;

import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

public class MockReadModel implements ReadModel<MockDomainEvent> {
	
	private static int TOTAL_EVENT_COUNT_OVER_ALL_INSTANCES = 0;

	private String name;
	private int eventCount = 0;
	private ThreadLocal<Integer> eventCountPerThread = new ThreadLocal<>();
	
	private EventQuery eventQuery;
	
	public MockReadModel ( String name, List<Class<?>> queriedClasses ) {
		this.name = name;
		this.eventQuery = EventQuery.forEvents(EventTypesFilter.of(queriedClasses), Tags.none());
	}
	
	public MockReadModel ( String name ) {
		this.name = name;
		this.eventQuery = EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
	}

	@Override
	public EventQuery eventQuery() {
		return eventQuery;
	}

	@Override
	public synchronized void when(MockDomainEvent event) {
		eventCount++;
		
		Integer current = eventCountPerThread.get();
		if ( current == null ) {
			current = 0;
		}
		eventCountPerThread.set(++current);
		
		TOTAL_EVENT_COUNT_OVER_ALL_INSTANCES++;
	}
	
	@Override
	public String readmodelName ( ) {
		return name;
	}
	
	public String name ( ) {
		return name;
	}
	
	public int eventCount ( ) {
		return eventCount;
	}

	public int eventCountForThisThread ( ) {
		return eventCountPerThread.get()==null?0:eventCountPerThread.get();
	}

	public static int TOTAL_EVENT_COUNT_OVER_INSTANCES ( ) {
		return TOTAL_EVENT_COUNT_OVER_ALL_INSTANCES;
	}
	
	public static void reset ( ) {
		TOTAL_EVENT_COUNT_OVER_ALL_INSTANCES = 0;
	}

}
