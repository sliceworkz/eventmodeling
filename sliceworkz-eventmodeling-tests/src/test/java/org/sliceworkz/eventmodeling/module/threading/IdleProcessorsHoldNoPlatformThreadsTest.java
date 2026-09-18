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
package org.sliceworkz.eventmodeling.module.threading;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * A started bounded context full of idle processors costs no platform threads.
 * <p>
 * Every read model, automation, translator and dispatcher runs its loop on a virtual thread, and an
 * idle loop is parked. Parked in {@code Object.wait()} inside a {@code synchronized} block, a virtual
 * thread pins its carrier on Java 21 through 23: the platform thread count then grows by one per idle
 * processor, and stops growing at the scheduler's {@code maxPoolSize} — beyond which processors are
 * simply never scheduled. Parked on {@link Parking} it holds nothing, which is what this test measures
 * through the real processors rather than through a stand-in: {@code ParkingTest} in the impl module
 * pins the mechanism, this pins that the processors use it. Platform threads are what
 * {@link ThreadMXBean#getThreadCount()} counts; virtual threads are not.
 * <p>
 * Framework behaviour, not storage behaviour, so a plain {@code @Test} against the in-memory store.
 */
public class IdleProcessorsHoldNoPlatformThreadsTest extends AbstractMockDomainTest {

	/** Enough idle processors that a platform thread per processor stands out against the carriers a busy start creates anyway. */
	private static final int READ_MODELS = 64;
	private static final int AUTOMATIONS = 4;
	private static final int PROCESSORS = READ_MODELS + AUTOMATIONS;

	@Test
	void aStartedContextFullOfIdleProcessorsHoldsNoPlatformThreadPerProcessor ( ) throws Exception {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		int before = threads.getThreadCount();

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		for ( int i = 0; i < READ_MODELS; i++ ) {
			builder.readmodel(new MockReadModel("idle read model " + i, ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		}
		List<IdleTodoList> todoLists = List.of(new IdleTodoList("idle todo list 1"), new IdleTodoList("idle todo list 2"), new IdleTodoList("idle todo list 3"), new IdleTodoList("idle todo list 4"));
		todoLists.forEach(todoList -> builder.readmodel(todoList).eventuallyConsistent());
		builder.automation(new FirstIdleAutomation(todoLists.get(0)));
		builder.automation(new SecondIdleAutomation(todoLists.get(1)));
		builder.automation(new ThirdIdleAutomation(todoLists.get(2)));
		builder.automation(new FourthIdleAutomation(todoLists.get(3)));

		buildBoundedContext(builder); // builds and starts: start() returns once every ephemeral read model has caught up
		Thread.sleep(1_000); // and the automations have read their bookmarks and parked

		int growth = threads.getThreadCount() - before;

		// The scheduler creates carriers for the virtual threads to run on at all, up to the number of
		// cores, so the bound is the larger of a quarter of the processors and that: on a machine with
		// as many cores as processors this says less, but it never fails for a healthy reason. What
		// must not happen is one platform thread per idle processor.
		int tolerated = Math.max(PROCESSORS / 4, Runtime.getRuntime().availableProcessors() + 4);
		assertTrue(growth <= tolerated,
				"starting a context with " + PROCESSORS + " idle processors grew the platform thread count by " + growth
				+ " (tolerated " + tolerated + ") -- a parked processor is pinning its carrier");
	}

	/** A todo list that never has anything on it, so its automation parks for its poll interval. */
	static class IdleTodoList implements TodoListReadModel<MockDomainEvent,String> {

		private final String name;
		private final List<String> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		IdleTodoList ( String name ) {
			this.name = name;
		}

		@Override
		public String readmodelName ( ) {
			return name;
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.EPHEMERAL;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			lastEventReference = event.reference();
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return List.copyOf(items).stream().limit(limit.value());
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}

	/** An automation's name is its class, so each idle one is a class of its own. */
	abstract static class IdleAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final TodoListReadModel<MockDomainEvent,String> todoList;

		IdleAutomation ( TodoListReadModel<MockDomainEvent,String> todoList ) {
			this.todoList = todoList;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return Optional.empty();
		}
	}

	static class FirstIdleAutomation extends IdleAutomation {
		FirstIdleAutomation ( TodoListReadModel<MockDomainEvent,String> todoList ) { super(todoList); }
	}

	static class SecondIdleAutomation extends IdleAutomation {
		SecondIdleAutomation ( TodoListReadModel<MockDomainEvent,String> todoList ) { super(todoList); }
	}

	static class ThirdIdleAutomation extends IdleAutomation {
		ThirdIdleAutomation ( TodoListReadModel<MockDomainEvent,String> todoList ) { super(todoList); }
	}

	static class FourthIdleAutomation extends IdleAutomation {
		FourthIdleAutomation ( TodoListReadModel<MockDomainEvent,String> todoList ) { super(todoList); }
	}

}
