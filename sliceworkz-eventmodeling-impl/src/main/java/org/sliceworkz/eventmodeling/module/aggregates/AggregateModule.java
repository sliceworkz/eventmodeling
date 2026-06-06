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
package org.sliceworkz.eventmodeling.module.aggregates;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.snapshots.SnapshotCapable;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventstore.events.EventReference;
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
	private ConcurrentHashMap<String, Counter> domainEventCounters = new ConcurrentHashMap<>();
	private BoundedContextEventEmitter eventEmitter;

	public AggregateModule ( String boundedContext, Instance instance, List<? extends AggregateSpecificationImpl<?>> aggregateSpecifications, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, MeterRegistry meterRegistry, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.domainEventStream = domainEventStream;
		this.meterRegistry = meterRegistry;
		this.eventEmitter = eventEmitter;
		
		io.micrometer.core.instrument.Tags tags = io.micrometer.core.instrument.Tags
				.of("context", boundedContext);

		aggregateSpecifications.forEach(spec->{
			if ( aggregateInfoByClass.containsKey(spec.aggregateClass()) ) {
				throw new IllegalArgumentException("duplicate aggregate registration for '%s'".formatted(spec.aggregateClass()));
			}
			try {
				
				var aggregateTags = tags.and(io.micrometer.core.instrument.Tags.of("aggregate", spec.aggregateClass().getSimpleName())); 
				
				Counter counterLoad = meterRegistry.counter("sliceworkz.eventmodeling.aggregate.load.count", aggregateTags);
				Counter counterSnapshotRead = meterRegistry.counter("sliceworkz.eventmodeling.aggregate.snapshot.read.count", aggregateTags);
				Counter counterSnapshotWrite = meterRegistry.counter("sliceworkz.eventmodeling.aggregate.snapshot.write.count", aggregateTags);
				Timer timer = meterRegistry.timer("sliceworkz.eventmodeling.aggregate.load.duration", aggregateTags);
				
				Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass = (Class<? extends Aggregate<DOMAIN_EVENT_TYPE>>) (Class<?>) spec.aggregateClass();
				AggregateInfo<DOMAIN_EVENT_TYPE> aggregateInfo =
						new AggregateInfo<>(
								aggregateClass.getSimpleName(),
								aggregateClass.getDeclaredConstructor(new Class[] {}),
								spec.snapshotStorage(),
								spec.readSnapshots(),
								spec.writeSnapshots(),
								spec.snapshotEventCountThreshold(),
								counterLoad,
								counterSnapshotRead,
								counterSnapshotWrite,
								timer);

				aggregateInfoByClass.put(aggregateClass, aggregateInfo);
			} catch (NoSuchMethodException | SecurityException e) {
				LOGGER.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
		});
		
		LOGGER.info("aggregates: %s".formatted(aggregateInfoByClass.keySet()));
	}
	
	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity) {
		return aggregate(aggregateClass, identity, Tracing.init(instance));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Aggregate<DOMAIN_EVENT_TYPE>> T aggregate(Class<T> aggregateClass, Tags identity, Tracing tracing) {
		tracing = tracing.instance(instance);

		if ( aggregateInfoByClass.containsKey(aggregateClass)) {

			if ( identity == null || identity.tags().size() < 1 ) {
				throw new IllegalArgumentException("aggregate identity needs at least one tag, got '%s'".formatted(identity));
			}

			AggregateInfo<DOMAIN_EVENT_TYPE> aggregateInfo = aggregateInfoByClass.get(aggregateClass);

			String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
			String cacheKey = aggregateInfo.name() + ":" + channel;

			Counter counter = domainEventCounters.computeIfAbsent(cacheKey, key ->
				meterRegistry.counter("sliceworkz.eventmodeling.aggregate.load.count",
					io.micrometer.core.instrument.Tags.of("context", boundedContext, "aggregate", aggregateInfo.name(), "channel", channel)));
			counter.increment();

			final Tracing finalTracing = tracing;
			return aggregateInfo.timer().record(()->{
				T result;
				try {
					result = (T) aggregateInfo.constructor().newInstance(new Object[] {});

					EventReference lastEventReference = null;

					if ( aggregateInfo.readSnapshots() && result instanceof SnapshotCapable snapshotCapable ) {
						String key = snapshotCapable.key(aggregateInfo.name(), identity);
						String version = snapshotCapable.version();
						var loadedSnapshot = aggregateInfo.snapshotStorage().load(key, version);
						if ( loadedSnapshot.isPresent() ) {
							aggregateInfo.counterSnapshotRead.increment();
							snapshotCapable.fromSnapshot(loadedSnapshot.get().snapshot());
							lastEventReference = loadedSnapshot.get().lastEventReference();
						}
					}

					AggregateContextImpl<DOMAIN_EVENT_TYPE> aci = new AggregateContextImpl<DOMAIN_EVENT_TYPE> (
							boundedContext,
							instance,
							aggregateInfo.name(),
							identity,
							result,
							domainEventStream,
							lastEventReference,
							aggregateInfo.snapshotStorageForWrite(),
							aggregateInfo.snapshotEventCountThreshold(),
							aggregateInfo.counterSnapshotWrite,
							meterRegistry,
							domainEventCounters,
							finalTracing,
							eventEmitter);
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
				String name,
				Constructor<? extends Aggregate<DOMAIN_EVENT_TYPE>> constructor,
				SnapshotStorage<Object> snapshotStorage,
				boolean readSnapshots,
				boolean writeSnapshots,
				int snapshotEventCountThreshold,
				Counter counter,
				Counter counterSnapshotRead,
				Counter counterSnapshotWrite,
				Timer timer
			) {
		
		public SnapshotStorage<Object> snapshotStorageForWrite ( ) {
			return writeSnapshots?snapshotStorage:null;
		}
		
	}
	
}
