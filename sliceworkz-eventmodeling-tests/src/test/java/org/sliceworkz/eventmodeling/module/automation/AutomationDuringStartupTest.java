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
package org.sliceworkz.eventmodeling.module.automation;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * An automation over an in-memory (EPHEMERAL) todo list, started over a long history: does it act on the
 * todo list before that list has been projected up to the head of the stream?
 * <p>
 * The history puts {@code item-x} on the list, then holds a long tail of events, and ends with
 * {@code item-x} being resolved by someone else, and {@code item-y} put on the list. A todo list projected
 * to the end holds {@code item-y} alone, so that is the one item the automation may handle -- and must.
 */
public class AutomationDuringStartupTest extends AbstractMockDomainTest {

	private static final int TAIL = 20_000;

	@Test
	void anAutomationDoesNotActOnATodoListStillCatchingUp ( ) {
		runOverALongHistory(false);
	}

	@Test
	void anAutomationDoesNotActOnATodoListStillCatchingUpDuringABackgroundStart ( ) {
		runOverALongHistory(true);
	}

	private void runOverALongHistory ( boolean inBackground ) {
		EventStream<MockDomainEvent> domainStream = EventStore.on(eventStorage()).build()
				.getEventStream(BoundedContextStreams.domain("UnitTestBoundedContext"), MockDomainEvent.class);

		domainStream.append(Event.of(new FirstDomainEvent("item-x"), Tags.none()));
		List<EphemeralEvent<MockDomainEvent>> tail = new ArrayList<>();
		for ( int i = 0; i < TAIL; i++ ) {
			tail.add(Event.of(new SecondDomainEvent("noise-" + i), Tags.none()));
			if ( tail.size() == 1000 ) {
				domainStream.append(tail);
				tail.clear();
			}
		}
		domainStream.append(Event.of(new SecondDomainEvent("item-x"), Tags.none())); // resolved elsewhere
		domainStream.append(Event.of(new FirstDomainEvent("item-y"), Tags.none()));  // still open at the head

		OpenItems todoList = new OpenItems();
		List<String> handled = Collections.synchronizedList(new ArrayList<>());

		var builder = BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new RecordingAutomation(todoList, handled));
		Mock domain = (Mock) builder.build();
		boundedContext = domain;
		if ( inBackground ) {
			domain.startInBackground(); // returns while the todo list is still catching up
		} else {
			domain.start(); // returns once the EPHEMERAL todo list is projected
		}

		await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains("item-y"));
		assertEquals(Set.of("item-y"), todoList.open(), "projected to the end, the todo list holds the open item only");
		assertFalse(handled.contains("item-x"),
				"the automation handled an item resolved later in the history, on a todo list that had not caught up yet: " + handled);
	}

	static class RecordingAutomation implements Automation<String,MockDomainEvent,MockOutboundEvent> {

		private final OpenItems todoList;
		private final List<String> handled;

		RecordingAutomation ( OpenItems todoList, List<String> handled ) {
			this.todoList = todoList;
			this.handled = handled;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,String> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( String todoItem, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			handled.add(todoItem); // an external call would go here: a payment, an e-mail
			return Optional.empty();
		}
	}

	/** Opened by a {@link FirstDomainEvent}, resolved by a {@link SecondDomainEvent} of the same value. */
	static class OpenItems implements TodoListReadModel<MockDomainEvent,String> {

		private final Set<String> open = Collections.synchronizedSet(new LinkedHashSet<>());
		private volatile EventReference lastEventReference;
		private int seen;

		@Override
		public String readmodelName ( ) {
			return "open-items";
		}

		@Override
		public ReadModelStorage storage ( ) {
			return ReadModelStorage.EPHEMERAL;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			switch ( event.data() ) {
				case FirstDomainEvent f -> open.add(f.value());
				case SecondDomainEvent s -> open.remove(s.value());
				default -> { }
			}
			lastEventReference = event.reference();
			if ( ++seen % 100 == 0 ) {
				pause(); // a long history takes a while to project; this stands in for it
			}
		}

		Set<String> open ( ) {
			synchronized ( open ) {
				return Set.copyOf(open);
			}
		}

		@Override
		public Stream<String> streamItems ( Limit limit ) {
			return open().stream().limit(limit.value());
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}

		private static void pause ( ) {
			try {
				Thread.sleep(5);
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
