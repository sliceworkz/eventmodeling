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
package org.sliceworkz.eventmodeling.module.aggregates;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateCapability;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;

// TODO add micrometer for observability
public class AggregateModule<DOMAIN_EVENT_TYPE> implements AggregateCapability<DOMAIN_EVENT_TYPE> {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AggregateModule.class);

	private Map<Class<? extends Aggregate<DOMAIN_EVENT_TYPE>>,Constructor<? extends Aggregate<DOMAIN_EVENT_TYPE>>> aggregateClassesWithConstructor = new HashMap<>();
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private String boundedContext;
	
	public AggregateModule ( String boundedContext, Collection<Class<? extends Aggregate<DOMAIN_EVENT_TYPE>>> aggregateClasses, EventStream<DOMAIN_EVENT_TYPE> domainEventStream ) {
		this.boundedContext = boundedContext;
		this.domainEventStream = domainEventStream;
		aggregateClasses.forEach(aggregateClass->{
			try {
				aggregateClassesWithConstructor.put(aggregateClass, aggregateClass.getDeclaredConstructor(new Class[] {}));
			} catch (NoSuchMethodException | SecurityException e) {
				LOGGER.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
		});
		
		LOGGER.info("aggregates: %s".formatted(aggregateClasses));
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity) {
		
		if ( aggregateClassesWithConstructor.containsKey(aggregateClass)) {
			
			if ( identity == null || identity.tags().size() < 1 ) {
				throw new IllegalArgumentException("aggregate identity needs at least one tag, got '%s'".formatted(identity));
			}
			
			T result;
			try {
				result = (T) aggregateClassesWithConstructor.get(aggregateClass).newInstance(new Object[] {});
				
				AggregateContextImpl<DOMAIN_EVENT_TYPE> aci = new AggregateContextImpl<DOMAIN_EVENT_TYPE> (identity, result, domainEventStream);
				result.setContext(aci);
				aci.updateFromStream();
				
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | SecurityException e) {
				LOGGER.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
			return result;
		} else {
			throw new IllegalArgumentException("aggregate class '%s' not registered in bounded context '%s'".formatted(aggregateClass, boundedContext));
		}
	}

}
