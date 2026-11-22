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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CUSTOMER;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.readmodels.CustomerValuesReadModel.CustomerId;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A {@code CachingReadModel} registered on a bounded context: read by its class, loaded on a miss, kept
 * current by its projector, started at the stream head, and degraded into loading every read while its
 * projector is stopped.
 */
public class CachingReadModelInABoundedContextTest extends AbstractMockDomainTest {

	private static final String CONTEXT = "UnitTestBoundedContext";
	private static final CustomerId C1 = CUSTOMER.id("c1");
	private static final CustomerId C2 = CUSTOMER.id("c2");

	@ForEachBackend
	void aHeldKeyIsKeptCurrentByTheProjector ( ) {
		CustomerValuesReadModel registered = new CustomerValuesReadModel();
		Mock mock = buildBoundedContext(builder().readmodel(registered).eventuallyConsistent());
		append(mock, C1, "a");

		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		assertSame(registered, readModel);
		assertEquals(List.of("a"), readModel.get(C1));

		EventReference b = append(mock, C1, "b");
		append(mock, C2, "not held");
		waitBecauseOfEventualConsistency(() -> readModel.result(C1).includes(b));

		assertEquals(List.of("a", "b"), readModel.get(C1));
		assertEquals(1, readModel.statistics().misses(), "kept current, never loaded again");
		assertFalse(readModel.holds(C2));
	}

	/**
	 * Nothing is held at start, so the history could update nothing: the projector starts at the head
	 * instead of replaying it, which is what keeps a start over ten million customers from reading them all.
	 */
	@ForEachBackend
	void startingDoesNotReplayTheHistory ( ) {
		var stream = eventStore().getEventStream(BoundedContextStreams.domain(CONTEXT), MockDomainEvent.class);
		for ( int i = 0; i < 20; i++ ) {
			stream.append(EphemeralEvent.of(new FirstDomainEvent("v" + i), Tags.of(CUSTOMER.tag(C1))));
		}

		Mock mock = buildBoundedContext(builder().readmodel(new CustomerValuesReadModel()).eventuallyConsistent());

		assertTrue(bookmarkOf(stream.getBookmarks()).map(b -> b.reference().isEmpty()).orElse(true),
				"the projector handled none of the history");
		assertEquals(20, mock.read(CustomerValuesReadModel.class).get(C1).size(), "while a read loads all of it");
	}

	@ForEachBackend
	void aStoppedProjectorDegradesIntoLoadingEveryReadUntilItRunsAgain ( ) {
		Mock mock = buildBoundedContext(builder().readmodel(new CustomerValuesReadModel()).eventuallyConsistent());
		CustomerValuesReadModel readModel = mock.read(CustomerValuesReadModel.class);
		append(mock, C1, "a");
		readModel.get(C1);

		assertTrue(mock.stopProcessor(ProcessorKind.READ_MODEL, "CustomerValuesReadModel"));
		assertFalse(readModel.holds(C1), "nothing is kept current, so nothing is held");
		append(mock, C1, "while stopped");
		assertEquals(List.of("a", "while stopped"), readModel.get(C1), "a read loads");
		assertFalse(readModel.holds(C1));

		assertTrue(mock.restartProcessor(ProcessorKind.READ_MODEL, "CustomerValuesReadModel"));
		waitBecauseOfEventualConsistency(() -> !readModel.statistics().halted());
		readModel.get(C1);
		EventReference after = append(mock, C1, "after restart");
		waitBecauseOfEventualConsistency(() -> readModel.result(C1).includes(after));
		assertEquals(List.of("a", "while stopped", "after restart"), readModel.get(C1));
	}

	@Test
	void aLiveRegistrationIsRefused ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> builder().readmodel(CustomerValuesReadModel.class).live());
		assertTrue(e.getMessage().contains("eventuallyConsistent()"), e.getMessage());
	}

	private EventReference append ( Mock mock, CustomerId customer, String value ) {
		return mock.event(EphemeralEvent.of(new FirstDomainEvent(value), Tags.of(CUSTOMER.tag(customer)))).orElseThrow();
	}

	private static java.util.Optional<Bookmark> bookmarkOf ( List<Bookmark> bookmarks ) {
		return bookmarks.stream().filter(b -> b.reader().contains("CustomerValuesReadModel")).findFirst();
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

}
