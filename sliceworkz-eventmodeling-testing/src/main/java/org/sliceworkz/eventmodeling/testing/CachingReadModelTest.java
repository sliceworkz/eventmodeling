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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.function.Consumer;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.readmodels.CacheStatistics;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;

/**
 * The base for testing a {@link CachingReadModel}: given events, read a key, and say when the projector
 * delivers — so a test states whether a read was answered from memory or loaded, and what it held.
 *
 * <pre>{@code
 * given()
 *     .event(new AccountOpened(A, ...))
 *     .whenRead(A).loaded().is(BigDecimal.ZERO)
 * .and()
 *     .event(new MoneyDeposited(A, TEN, ...))
 *     .whenRead(A).fromMemory().is(BigDecimal.ZERO)       // the projector has not delivered it yet
 * .and()
 *     .projected()
 *     .whenRead(A).fromMemory().is(TEN);                  // now it has, and nothing was loaded again
 * }</pre>
 *
 * <p><b>The projector is the test's, and runs only when the test says {@code projected()}.</b> The read
 * model is never registered on the bounded context — a registered one runs on a processor thread of its
 * own, which would race the test — but attached to the context's domain stream, given its starting
 * point by {@code resumeFrom()} as a deployment gives it, and projected by an eventstore
 * {@link Projector} from there: the code a deployed processor runs, delivered when the test asks. That
 * makes the lag between an append and a held key seeing it a step of the test rather than a timing.
 *
 * <p>The read model is created by {@link #readModel()} on the first call of a test, before the events
 * it is given: as in a deployment started before them, a load reads them and the projector then
 * delivers them again, which the read model counts once — so every test exercises that rule too.
 *
 * <p>Extend it like the other bases, and annotate a scenario {@code @ForEachBackend} to run it on every
 * backend registered, which matters for a miss: it is a tag query against the storage.
 */
public abstract class CachingReadModelTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE, KEY, STATE>
		extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	private CachingReadModel<DOMAIN_EVENT_TYPE, KEY, STATE> underTest;
	private Projector<DOMAIN_EVENT_TYPE> projector;

	/** The read model under test. Called once per test method: return a fresh instance, never a shared one. */
	public abstract CachingReadModel<DOMAIN_EVENT_TYPE, KEY, STATE> readModel ( );

	/** Nothing to register: the harness owns the read model and its projector. */
	@Override
	public void configure ( BoundedContextBuilder<?> builder ) {
	}

	public Scenario given ( ) {
		readModelUnderTest();
		return new Scenario();
	}

	/** The read model under test, attached and started at the stream head — for its statistics, say. */
	protected CachingReadModel<DOMAIN_EVENT_TYPE, KEY, STATE> readModelUnderTest ( ) {
		if ( underTest == null ) {
			underTest = readModel();
			underTest.attach(domainStream());
			EventReference head = underTest.resumeFrom().orElse(null);
			projector = Projector.from(domainStream()).into(underTest).startingAfter(head).build();
		}
		return underTest;
	}

	public class Scenario {

		/** Appends domain events, stating their own tags. The projector does not deliver them yet. */
		@SafeVarargs
		public final Scenario events ( DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> kernel().event(e));
			return this;
		}

		/** Appends one domain event. */
		public Scenario event ( DOMAIN_EVENT_TYPE event ) {
			kernel().event(event);
			return this;
		}

		/** Appends one domain event with tags its payload does not state. */
		public Scenario event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(EphemeralEvent.of(event, tags));
			return this;
		}

		/** The projector delivers everything appended since it last ran, as a deployed one would. */
		public Scenario projected ( ) {
			projector.run();
			return this;
		}

		/** Reads a key, as a caller of the registered read model would. */
		public ReadAssertions whenRead ( KEY key ) {
			CacheStatistics before = readModelUnderTest().statistics();
			STATE state = readModelUnderTest().get(key);
			CacheStatistics after = readModelUnderTest().statistics();
			return new ReadAssertions(this, key, state, after.hits() > before.hits());
		}

	}

	public class ReadAssertions {

		private final Scenario scenario;
		private final KEY key;
		private final STATE state;
		private final boolean fromMemory;

		private ReadAssertions ( Scenario scenario, KEY key, STATE state, boolean fromMemory ) {
			this.scenario = scenario;
			this.key = key;
			this.state = state;
			this.fromMemory = fromMemory;
		}

		/** The key was held, and answered without reading the event store. */
		public ReadAssertions fromMemory ( ) {
			assertEquals(true, fromMemory, "expected key " + key + " to be answered from memory, but it was loaded");
			return this;
		}

		/** The key was not held, and was loaded from the event store. */
		public ReadAssertions loaded ( ) {
			assertEquals(false, fromMemory, "expected key " + key + " to be loaded, but it was answered from memory");
			return this;
		}

		/** The state read, compared as the other bases compare: field by field, with the ignore markers. */
		public ReadAssertions is ( STATE expected ) {
			assertCompareObjects(expected, state, "state of key " + key);
			return this;
		}

		public ReadAssertions satisfies ( Consumer<STATE> assertion ) {
			assertion.accept(state);
			return this;
		}

		/** Back to the scenario, for what happens next. */
		public Scenario and ( ) {
			return scenario;
		}

	}

}
