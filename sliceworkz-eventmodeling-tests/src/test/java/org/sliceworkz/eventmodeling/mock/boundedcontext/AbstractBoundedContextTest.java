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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.Untyped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Base for every test that builds a bounded context over an event storage.
 * <p>
 * The storage lifecycle comes from {@link AbstractEventStoreTest}: a fresh, empty store before each
 * test, released after it. Which storage that is depends on how the test methods are annotated.
 * <p>
 * <b>{@link ForEachBackend}</b> — the test runs once per {@link EventStoreBackend} registered in
 * {@code META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend}, and each invocation
 * is reported under the backend that produced it ({@code myScenario [postgres:18]}). Use it for
 * anything the framework must do the same way whatever the storage is; it is what replaced the
 * hand-written {@code @Nested OnPostgres17 / OnPostgres18} subclasses this suite used to carry.
 * <p>
 * <b>{@link org.junit.jupiter.api.Test @Test}</b> — the test runs once against the in-memory store,
 * per {@link #createEventStorage()} below. Use it when the scenario is about the framework rather
 * than about storage behaviour (duplicate-name validation, listener wiring), so it does not cost a
 * container run per backend to prove something no backend can change.
 * <p>
 * Subclasses reach the storage through {@link #eventStorage()} and must not build one themselves.
 */
public abstract class AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends AbstractEventStoreTest {

	protected BoundedContext<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> boundedContext;

	@Override
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		MockReadModel.reset();
	}

	@Override
	@AfterEach
	public void tearDown ( ) {
		releaseBoundedContext();
		super.tearDown();
	}

	/**
	 * Releases the bounded context before the storage under it goes away — one left running would
	 * keep projecting against a store that is being torn down. Override to
	 * {@link BoundedContext#terminate() terminate} instead of stopping.
	 */
	protected void releaseBoundedContext ( ) {
		if ( boundedContext != null ) {
			boundedContext.stop();
			boundedContext = null;
		}
	}

	/**
	 * The in-memory storage a plain {@code @Test} runs against.
	 * <p>
	 * {@link AbstractEventStoreTest} would otherwise demand a bound backend and fail the test with
	 * an explanation; overriding it here makes the in-memory store the default and leaves
	 * {@link ForEachBackend} as the deliberate opt-in to the full matrix.
	 */
	@Override
	protected EventStorage createEventStorage ( ) {
		return hasBoundBackend() ? super.createEventStorage() : InMemoryEventStorage.newBuilder().build();
	}

	@Override
	protected void destroyEventStorage ( EventStorage storage ) {
		if ( hasBoundBackend() ) {
			super.destroyEventStorage(storage);
		}
	}

	/**
	 * Whether a {@link ForEachBackend} invocation bound a backend to this test.
	 * <p>
	 * {@link AbstractEventStoreTest#backend()} throws rather than returning {@code null} when none
	 * is bound, which is the right contract for a test that requires one but leaves no way to ask.
	 */
	private boolean hasBoundBackend ( ) {
		try {
			backend();
			return true;
		} catch (IllegalStateException noBackendBound) {
			return false;
		}
	}

	public abstract Class<DOMAIN_EVENT_TYPE> domainEventType ( );

	public abstract Class<INBOUND_EVENT_TYPE> inboundEventType ( );

	public abstract Class<OUTBOUND_EVENT_TYPE> outboundEventType ( );

	protected BoundedContext<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> boundedContext ( ) {
		return boundedContext;
	}

	protected BoundedContextBuilder<?> boundedContextBuilder ( EventStorage eventStorage ) {

		BoundedContextBuilder<?> builder =
				BoundedContext.newBuilder(Untyped.class)
				.eventTypes(domainEventType(), inboundEventType(), outboundEventType());

		builder
			.name("UnitTestBoundedContext")
			.eventStorage(eventStorage);

		return builder;
	}

}
