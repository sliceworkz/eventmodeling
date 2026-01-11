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
package org.sliceworkz.eventmodeling.benchmark;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingInboundEvent.OrderRegistered;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingOutboundEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.infra.postgres.DataSourceFactory;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public class BenchmarkApplication {
	
	private static final String BOUNDED_CONTEXT_NAME = "orderprocessing";
	
	public static final int EVENT_COUNT_PER_PRODUCER = 10000;
	public static final int PARALLEL_PRODUCERS = 4;
	
	public static final int TOTAL_EVENTS_INGESTED = EVENT_COUNT_PER_PRODUCER * PARALLEL_PRODUCERS;
	public static final int TOTAL_EVENTS_OUTBOUND_EXPECTED = TOTAL_EVENTS_INGESTED;
	
	public static final int TOTAL_EVENTS_EXPECTED = TOTAL_EVENTS_OUTBOUND_EXPECTED + 6*TOTAL_EVENTS_INGESTED;

	private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkApplication.class);
	
	public static void main ( String[] args ) throws InterruptedException {
		
		
		boolean initializeDatabase = true;
		if ( args.length > 0 ) {
			initializeDatabase = false;
		}
		System.out.println("initializeDatabase=" + initializeDatabase);
		boolean finalInitializeDatabase = initializeDatabase;
		
		/**
		 * Starting PrometheusRegistry and Javalin REST API to expose metrics 
		 */
		PrometheusMeterRegistry prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT); 
		
		// Javalin framework for REST
		var javalin = Javalin.create(config -> {
			config.useVirtualThreads = true; // Enables virtual threads for all request handling
		});

		if ( prometheusMeterRegistry != null ) {
	       // Expose metrics endpoint for Prometheus to scrape
	        javalin.get("/metrics", ctx -> {
	            ctx.contentType("text/plain; version=0.0.4")
	            	.result(prometheusMeterRegistry.scrape());
	        });
		
		}
		javalin.start(7072);
		
		LOGGER.info("starting ...");
		
//		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();
		DataSource dataSource = DataSourceFactory.fromConfiguration("pooled");
		EventStorage eventStorage = PostgresEventStorage.newBuilder().dataSource(dataSource).prefix("benchmark_").initializeDatabase(initializeDatabase).build();

		OrderProcessingBoundedContext bc = BoundedContext.newBuilder(OrderProcessingDomainEvent.class, OrderProcessingInboundEvent.class, OrderProcessingOutboundEvent.class)
				.meterRegistry(prometheusMeterRegistry)
				.name(BOUNDED_CONTEXT_NAME)
				.instance(InstanceFactory.determine(BOUNDED_CONTEXT_NAME))
				.eventStorage(eventStorage)
				.rootPackage(BenchmarkApplication.class.getPackage())
				.preConfigure(fs->((OrderProcessingFeatureSlice)fs).preConfigure(dataSource, finalInitializeDatabase))
				.build(OrderProcessingBoundedContext.class);
		
//		bc.<OrderProcessingFeatureSlice>getDeployedFeatureSlices().forEach(fs->fs.postConfigure(bc));
		
		bc.start();

		EventStream<Object> domainStream = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.forContext(BOUNDED_CONTEXT_NAME).withPurpose("domain"));


		ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_PRODUCERS);

		Instant start = Instant.now();
		
		// TODO: can we use Projector in ReadModelModule or in EventuallyConstistentProcessor or in AutomationProcessor?
		// TODO: do not fetch all to count but select latest (or other option?)
		// TODO: create command CreateShippingLabel instead of generating event
		// TODO: check micrometer on idempotency for inbound events?
		// TODO: cli monitor (?)
		// TODO: synchronized terug weghalen (?)
		
		AtomicInteger orderNumbering = new AtomicInteger();
		
		for ( int i = 0; i < PARALLEL_PRODUCERS; i++ ) {
		    executor.submit(()->{
		    	for ( int j = 0; j < EVENT_COUNT_PER_PRODUCER; j++ ) {
		    		long orderNumber = orderNumbering.incrementAndGet();
					bc.incoming(new OrderRegistered(orderNumber), Tag.of("idempotency", orderNumber).toString());
		    	}
		    });
		}

		executor.shutdown();
		
		while ( !executor.isTerminated() ) {
			System.err.println("events ingested  : %d".formatted(orderNumbering.get()));
			long totalEvents = domainStream.queryBackwards(EventQuery.matchAll(), Limit.to(1)).map(Event::reference).map(EventReference::position).findFirst().orElse(Long.valueOf(0));
			System.err.println("total events     : %d / %d".formatted(totalEvents, TOTAL_EVENTS_EXPECTED));
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
		System.err.println("events ingested  : %d".formatted(orderNumbering.get()));
		
		Instant sent = Instant.now();

		long totalEvents = 0;
		while ( totalEvents < TOTAL_EVENTS_EXPECTED ) {
			totalEvents = domainStream.queryBackwards(EventQuery.matchAll(), Limit.to(1)).findFirst().get().reference().position();
//			int eventsDomain = domainStream.query(EventQuery.matchAll()).toList().size();
//			eventsOutbound = outboundStream.query(EventQuery.matchAll()).toList().size();
//			System.err.println("events in domain : %d".formatted(eventsDomain));
//			System.err.println("events outbound  : %d".formatted(eventsOutbound));
			System.err.println("total events     : %d / %d".formatted(totalEvents, TOTAL_EVENTS_EXPECTED));
			Thread.sleep(5000);
		}
		
		Instant done = Instant.now();
		
//		int eventsDomain = domainStream.query(EventQuery.matchAll()).toList().size();
//		eventsOutbound = outboundStream.query(EventQuery.matchAll()).toList().size();
//		System.err.println("DONE events in domain : %d".formatted(eventsDomain));
//		System.err.println("DONE events outbound  : %d".formatted(eventsOutbound));

		
		long sendingMs = sent.toEpochMilli() - start.toEpochMilli();
		long totalMs = done.toEpochMilli() - start.toEpochMilli();
		
		System.err.println("ingestion time . : %d ms".formatted(sendingMs));
		System.err.println("total events     : %d".formatted(totalEvents));
		System.err.println("total time ..... : %d ms".formatted(totalMs));
		
		long ingestedPerSec = Math.round(( TOTAL_EVENTS_INGESTED * 1000 ) / (double)sendingMs);
		long eventsPerSec = Math.round(( TOTAL_EVENTS_EXPECTED * 1000 ) / (double)totalMs);
		long workflowsPerSec = Math.round(( TOTAL_EVENTS_OUTBOUND_EXPECTED * 1000 ) / (double)totalMs);

		System.err.println("ingestion rate . : %d / sec".formatted(ingestedPerSec));
		System.err.println("event rate ..... : %d / sec".formatted(eventsPerSec));
		System.err.println("workflow rate .. : %d / sec".formatted(workflowsPerSec));
		
		
		bc.stop();
		LOGGER.info("done.  press any key");
		
		try {
			System.in.read();
		} catch (IOException e) {
		}
		
		javalin.stop();

		LOGGER.info("exited.");
	}

}
