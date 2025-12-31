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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class AggregateModule<DOMAIN_EVENT_TYPE> implements AggregateCapability<DOMAIN_EVENT_TYPE> {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AggregateModule.class);

	private Map<Class<? extends Aggregate<DOMAIN_EVENT_TYPE>>,AggregateInfo<DOMAIN_EVENT_TYPE>> aggregateInfoByClass = new HashMap<>();
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private String boundedContext;
	private Instance instance;
	private MeterRegistry meterRegistry;
	
	public AggregateModule ( String boundedContext, Instance instance, List<? extends AggregateSpecificationImpl<DOMAIN_EVENT_TYPE,?,?>> aggregateSpecifications, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, MeterRegistry meterRegistry ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.domainEventStream = domainEventStream;
		this.meterRegistry = meterRegistry;
		
		io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
				.of("context", boundedContext);

		aggregateSpecifications.forEach(spec->{
			if ( aggregateInfoByClass.containsKey(spec.aggregateClass()) ) {
				throw new IllegalArgumentException("duplicate aggregate registration for '%s'".formatted(spec.aggregateClass()));
			}
			try {
				
				var aggregateTags = tags.and(io.micrometer.core.instrument.Tags.of("aggregate", spec.aggregateClass().getSimpleName())); 
				
				Counter counter = meterRegistry.counter("sliceworkz.eventmodeling.aggregate.load.count", aggregateTags);
				Timer timer = meterRegistry.timer("sliceworkz.eventmodeling.aggregate.load.duration", aggregateTags);
				
				AggregateInfo<DOMAIN_EVENT_TYPE> aggregateInfo = new AggregateInfo<>(spec.aggregateClass().getDeclaredConstructor(new Class[] {}), counter, timer);
				
				aggregateInfoByClass.put(spec.aggregateClass(), aggregateInfo);
			} catch (NoSuchMethodException | SecurityException e) {
				LOGGER.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
		});
		
		LOGGER.info("aggregates: %s".formatted(aggregateInfoByClass.keySet()));
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity) {
		
		if ( aggregateInfoByClass.containsKey(aggregateClass)) {
			
			if ( identity == null || identity.tags().size() < 1 ) {
				throw new IllegalArgumentException("aggregate identity needs at least one tag, got '%s'".formatted(identity));
			}
			
			AggregateInfo<DOMAIN_EVENT_TYPE> aggregateInfo = aggregateInfoByClass.get(aggregateClass);
			
			aggregateInfo.counter().increment();
			
			return aggregateInfo.timer().record(()->{
				T result;
				try {
					result = (T) aggregateInfo.constructor().newInstance(new Object[] {});
					
					AggregateContextImpl<DOMAIN_EVENT_TYPE> aci = new AggregateContextImpl<DOMAIN_EVENT_TYPE> (boundedContext, instance, identity, result, domainEventStream);
					result.setContext(aci);
					aci.updateFromStream();
					
				} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
						| InvocationTargetException | SecurityException e) {
					LOGGER.error(e.getMessage(), e);
					throw new RuntimeException(e);
				}
				return result;
			});
			
		} else {
			throw new IllegalArgumentException("aggregate class '%s' not registered in bounded context '%s'".formatted(aggregateClass, boundedContext));
		}
	}

	public record AggregateInfo<DOMAIN_EVENT_TYPE> (
				Constructor<? extends Aggregate<DOMAIN_EVENT_TYPE>> constructor,
				Counter counter,
				Timer timer
			) { }
	
}
