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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Not a test: a measurement of what a command with several savepoint decision models costs, in storage
 * reads and in time, run on demand with {@code -Dmeasure=true}. It asserts nothing and prints one line per
 * backend.
 * <p>
 * The command decides on three savepoint models — the newest {@code Third} of an entity, then that
 * entity's {@code First}s and {@code Second}s after it — and one plain model, over a history of 20.000
 * events across 23 entities with a savepoint every 100 events of an entity.
 */
@EnabledIfSystemProperty(named = "measure", matches = "true")
public class SavepointReadsMeasurement extends AbstractMockDomainTest {

	private static final int ENTITIES = 20;
	private static final int EVENTS = 20000;
	private static final int WARMUP = 50;
	private static final int MEASURED = 300;

	private InvocationCountingEventStorage countingStorage;
	private EventStream<MockDomainEvent> directStream;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		this.countingStorage = new InvocationCountingEventStorage(eventStorage());
		this.directStream = EventStore.on(eventStorage()).build()
				.getEventStream(BoundedContextStreams.domain("UnitTestBoundedContext"), MockDomainEvent.class);
	}

	static class SinceSavepoint implements DecisionModel<MockDomainEvent> {
		final String entity;
		int seen;
		SinceSavepoint ( String entity ) { this.entity = entity; }
		@Override public EventQuery initQuery ( ) { return EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class).tagged("entity", entity)).backwards().limit(1); }
		@Override public EventQuery eventQuery ( ) { return EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class, SecondDomainEvent.class).tagged("entity", entity)); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	static class Plain implements DecisionModel<MockDomainEvent> {
		int seen;
		@Override public EventQuery eventQuery ( ) { return EventQuery.of(EventFilter.forTypes(SecondDomainEvent.class).tagged("account", "a1")); }
		@Override public void when ( Event<MockDomainEvent> event ) { seen++; }
	}

	private static Command<MockDomainEvent> decide ( ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.decisionModels(new SinceSavepoint("e1"), new SinceSavepoint("e2"), new SinceSavepoint("e3"), new Plain());
				result.raiseEvent(new SecondDomainEvent("decided"), Tags.of("decided", "yes"));
			}
		};
	}

	@ForEachBackend
	void measure ( ) {
		seed();
		Mock domain = buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(countingStorage)
				.instance(InstanceFactory.determine("unittests")));

		for ( int i = 0; i < WARMUP; i++ ) {
			domain.execute(decide());
		}
		int readsBefore = countingStorage.queriesDone();
		long start = System.nanoTime();
		for ( int i = 0; i < MEASURED; i++ ) {
			domain.execute(decide());
		}
		long micros = ( System.nanoTime() - start ) / 1000 / MEASURED;
		double reads = ( countingStorage.queriesDone() - readsBefore ) / (double) MEASURED;
		System.out.printf("MEASUREMENT savepoint-reads backend=%s readsPerCommand=%.1f microsPerCommand=%d%n",
				backendName(), reads, micros);
	}

	private void seed ( ) {
		List<EphemeralEvent<MockDomainEvent>> batch = new ArrayList<>();
		for ( int n = 0; n < EVENTS; n++ ) {
			String entity = "e" + ( n % ( ENTITIES + 3 ) + 1 );
			Tags tags = Tags.of("entity", entity, "account", n % 7 == 0 ? "a1" : "a2");
			MockDomainEvent data = ( n / ( ENTITIES + 3 ) ) % 100 == 99 ? new ThirdDomainEvent("count")
					: n % 2 == 0 ? new FirstDomainEvent("f") : new SecondDomainEvent("s");
			batch.add(Event.of(data, tags));
			if ( batch.size() == 500 ) {
				directStream.append(AppendCriteria.none(), batch);
				batch.clear();
			}
		}
		if ( !batch.isEmpty() ) {
			directStream.append(AppendCriteria.none(), batch);
		}
	}

	private String backendName ( ) {
		return eventStorage().getClass().getSimpleName();
	}
}
