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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarted;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockCommand;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;

/**
 * Pins the {@link LifecycleCapability} state machine down: built → started ⇄ stopped → terminated.
 * <p>
 * A duplicate {@code start()} is a no-op rather than a second boot — an observer must not see the
 * context start twice, and the slices must not be wired twice. A {@code start()} after
 * {@code terminate()} throws, because the terminated context has closed its {@code EventStore} and
 * drained its processor threads: "started" would mean looking alive while reading through a closed
 * store and doing nothing. The {@code stop()} → {@code start()} restart path stays supported, and
 * {@code stop()} on a context that is not running is tolerated, matching {@code terminate()}'s own
 * idempotence.
 * <p>
 * Plain {@code @Test}s: this is framework behaviour, no storage backend can change it.
 */
public class BoundedContextLifecycleTest extends AbstractMockDomainTest {

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private Mock buildAndStart ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("LifecycleTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		return buildBoundedContext(builder);
	}

	private long count ( Class<? extends BoundedContextEvent> eventType ) {
		return received.stream().filter(eventType::isInstance).count();
	}

	@Test
	void aSecondStartIsANoOpAndTheContextIsBootedOnce ( ) {
		Mock domain = buildAndStart();

		domain.start();

		assertEquals(1, count(BoundedContextEvent.BoundedContextStarting.class),
				"a second start() must not re-announce the context");
		assertEquals(1, count(BoundedContextStarted.class),
				"a second start() must not report a second boot");

		// and the context is still the running one, not something the duplicate start disturbed
		assertTrue(domain.execute(new MockCommand(List.of(new FirstDomainEvent("after-duplicate-start")))).isPresent());
	}

	@Test
	void stopThenStartRestartsTheContext ( ) {
		Mock domain = buildAndStart();

		domain.stop();
		domain.start();

		assertEquals(2, count(BoundedContextStarted.class),
				"a restart is a real start and is announced as one");
		assertTrue(domain.execute(new MockCommand(List.of(new FirstDomainEvent("after-restart")))).isPresent(),
				"a stopped context must be startable again, and work afterwards");
	}

	@Test
	void startAfterTerminateThrows ( ) {
		Mock domain = buildAndStart();

		domain.terminate();

		assertThrows(IllegalStateException.class, domain::start,
				"terminate() is terminal: the EventStore is closed and the processor threads are gone, "
				+ "so a start that 'succeeded' would hand back a context that looks alive and does nothing");
	}

	@Test
	void terminateTwiceIsANoOp ( ) {
		Mock domain = buildAndStart();

		domain.terminate();
		assertDoesNotThrow(domain::terminate);

		assertEquals(1, count(BoundedContextEvent.BoundedContextStopping.class),
				"the second terminate() must not report a second shutdown");
		assertEquals(1, count(BoundedContextEvent.BoundedContextStopped.class));
	}

	@Test
	void stopWhenNotStartedIsANoOp ( ) {
		var builder = BoundedContext.newBuilder(Mock.class)
				.name("LifecycleTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		Mock domain = (Mock) builder.build();
		this.boundedContext = domain;

		assertDoesNotThrow(domain::stop, "stop() on a never-started context has nothing to do");

		// a never-started context is still startable: stop() must not have moved it anywhere
		domain.start();
		assertEquals(1, count(BoundedContextStarted.class));
	}

	@Test
	void stopAfterTerminateIsANoOp ( ) {
		Mock domain = buildAndStart();

		domain.terminate();

		assertDoesNotThrow(domain::stop, "terminate() already stopped everything; stop() has nothing left to do");
	}

}
