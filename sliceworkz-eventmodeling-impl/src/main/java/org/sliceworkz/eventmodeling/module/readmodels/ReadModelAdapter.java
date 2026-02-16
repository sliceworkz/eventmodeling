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
package org.sliceworkz.eventmodeling.module.readmodels;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.threading.EventuallyConsistentProcessorIdentification.Storage;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Adapter that wraps a ReadModelWithMetaData and adds Micrometer monitoring for
 * eventually consistent read model processing.
 *
 * Metrics recorded:
 * - sliceworkz.eventmodeling.readmodel.ec.update: Counter for each event processed
 * - sliceworkz.eventmodeling.readmodel.ec.duration: Timer for event processing duration
 * - sliceworkz.eventmodeling.readmodel.ec.batch: Counter for each batch processed
 * - sliceworkz.eventmodeling.readmodel.ec.batch.duration: Timer for batch processing duration
 * - sliceworkz.eventmodeling.readmodel.ec.batch.events: Counter for total events processed in batches
 */
class ReadModelAdapter<DOMAIN_EVENT_TYPE> implements BatchAwareProjection<DOMAIN_EVENT_TYPE> {

	private final ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel;
	private final String boundedContext;
	private final String readModelName;
	private final String readModelType;
	private final MeterRegistry meterRegistry;
	private final Tracing tracing;

	private final ConcurrentHashMap<String, Counter> eventCounters = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Timer> eventTimers = new ConcurrentHashMap<>();

	private final Counter batchCounter;
	private final Timer batchTimer;
	private final Counter batchEventsCounter;

	private Timer.Sample batchSample;
	private final AtomicLong batchEventCount = new AtomicLong(0);

	public ReadModelAdapter(ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel, String boundedContext, Storage storage, MeterRegistry meterRegistry, Tracing tracing) {
		this.readModel = readModel;
		this.boundedContext = boundedContext;
		this.readModelName = readModel.readmodelName();
		this.readModelType = storage.label();
		this.meterRegistry = meterRegistry;
		this.tracing = tracing;

		Tags baseTags = Tags.of("context", boundedContext, "readmodel", readModelName, "readmodeltype", readModelType);
		this.batchCounter = meterRegistry.counter("sliceworkz.eventmodeling.readmodel.ec.batch", baseTags);
		this.batchTimer = meterRegistry.timer("sliceworkz.eventmodeling.readmodel.ec.batch.duration", baseTags);
		this.batchEventsCounter = meterRegistry.counter("sliceworkz.eventmodeling.readmodel.ec.batch.events", baseTags);
	}

	@Override
	public void when(Event<DOMAIN_EVENT_TYPE> eventWithMeta) {
		String eventName = eventWithMeta.data().getClass().getSimpleName();
		String channel = tracing.channel() != null ? tracing.channel() : Tracing.UNKNOWN_CHANNEL_LABEL;
		String cacheKey = readModelName + ":" + readModelType + ":" + eventName + ":" + channel;

		Counter counter = eventCounters.computeIfAbsent(cacheKey, key ->
			meterRegistry.counter("sliceworkz.eventmodeling.readmodel.ec.update",
				Tags.of("context", boundedContext, "readmodel", readModelName, "readmodeltype", readModelType,
					"event", eventName, "channel", channel)));
		counter.increment();

		Timer timer = eventTimers.computeIfAbsent(cacheKey, key ->
			meterRegistry.timer("sliceworkz.eventmodeling.readmodel.ec.duration",
				Tags.of("context", boundedContext, "readmodel", readModelName, "readmodeltype", readModelType,
					"event", eventName, "channel", channel)));

		timer.record(() -> readModel.when(eventWithMeta));
		batchEventCount.incrementAndGet();
	}

	@Override
	public void beforeBatch() {
		batchSample = Timer.start(meterRegistry);
		batchEventCount.set(0);
		if (readModel instanceof BatchAwareProjection<?> batchAware) {
			batchAware.beforeBatch();
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> lastProcessedEvent) {
		if (batchSample != null) {
			batchSample.stop(batchTimer);
			batchSample = null;
		}
		long eventsProcessed = batchEventCount.getAndSet(0);
		if (eventsProcessed > 0) {
			batchCounter.increment();
			batchEventsCounter.increment(eventsProcessed);
		}
		if (readModel instanceof BatchAwareProjection<?> batchAware) {
			batchAware.afterBatch(lastProcessedEvent);
		}
	}

	@Override
	public void cancelBatch() {
		batchSample = null;
		batchEventCount.set(0);
		if (readModel instanceof BatchAwareProjection<?> batchAware) {
			batchAware.cancelBatch();
		}
	}

	@Override
	public EventQuery eventQuery() {
		return readModel.eventQuery();
	}
}
