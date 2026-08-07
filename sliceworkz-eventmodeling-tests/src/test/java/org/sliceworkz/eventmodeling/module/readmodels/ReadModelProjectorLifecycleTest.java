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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * A read model's projector has to be answerable from the event stream alone, and
 * {@code EventuallyConsistentReadModelUpdated} cannot do it on its own: it is raised only by a
 * catch-up that handled something, so a read model that is up to date and one whose projector died
 * are the same silence. {@code ReadModelProjectorStarted} and {@code ReadModelProjectorStopped} are
 * the pair that tells them apart, and this pins both ends.
 */
public class ReadModelProjectorLifecycleTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ProjectorLifecycleBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	/**
	 * The announcement is unconditional, which is the whole reason it exists: a read model matching
	 * none of the events in the stream never updates, and without this event would be
	 * indistinguishable from one that is not deployed at all.
	 */
	@Test
	void aProjectorAnnouncesItselfEvenWhenItHasNothingToProject ( ) {
		// queries an event type this test never appends, so nothing is ever handled for it
		MockReadModel idle = new MockReadModel("idle-readmodel", List.of(MockDomainEvent.SecondDomainEvent.class), ReadModelStorage.EPHEMERAL);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(idle).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("nothing this read model wants"));

		BoundedContextEvent.ReadModelProjectorStarted started = started("idle-readmodel");
		assertNotNull(started, "an eventually consistent read model must announce its projector at startup: " + received);
		assertEquals(CONTEXT_NAME, started.boundedContext());
		assertEquals("ephemeral", started.readModelType(),
				"the start is what says where a read model keeps its state before it has projected anything");

		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.EventuallyConsistentReadModelUpdated u
						&& u.readModel().equals("idle-readmodel")),
				"nothing was there to handle, so there is nothing but the start to go on: " + received);
	}

	/** The storage class travels with the event rather than being inferred, so a durable read model says so too. */
	@Test
	void theAnnouncementCarriesTheStorageClassTheReadModelDeclared ( ) {
		MockReadModel local = new MockReadModel("local-readmodel", ReadModelStorage.LOCAL);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(local).eventuallyConsistent();
		buildBoundedContext(builder);

		assertEquals("local", started("local-readmodel").readModelType());
	}

	/**
	 * The one this exists for. A projection that throws retires its processor for good — there is no
	 * per-item containment as an automation has, so its first failure is also its last — and until now
	 * it said so in a log line and nowhere else, leaving a read model that had stopped taking events
	 * looking exactly like one with nothing to do.
	 */
	@Test
	void aProjectionThatThrowsStopsTheProjectorAndSaysSo ( ) {
		ThrowingReadModel poison = new ThrowingReadModel("poison-readmodel");

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(poison).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("the one it dies on"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertNotNull(stopped("poison-readmodel"), "a retired projector must report it: " + received));

		BoundedContextEvent.ReadModelProjectorStopped stopped = stopped("poison-readmodel");
		assertEquals(CONTEXT_NAME, stopped.boundedContext());
		assertEquals("ephemeral", stopped.readModelType());
		assertNotNull(stopped.failure(), "the throwable that stopped it is the point of the event");
		// the cause, not the ProjectorException wrapping it — that wrapper is framework plumbing, and
		// reporting it would put the same type on every stopped read model there has ever been
		assertEquals(IllegalStateException.class.getName(), stopped.failure().type());
		assertEquals("this read model cannot project", stopped.failure().message());
		assertTrue(stopped.failure().stackTrace().contains("ThrowingReadModel"),
				"the stack trace must lead back to the projection: " + stopped.failure().stackTrace());
		assertNotNull(stopped.failedAt(), "the event being projected when it failed is what an operator needs next");

		assertNotNull(started("poison-readmodel"), "it started before it stopped: " + received);
	}

	/**
	 * The asymmetry {@code AutomationStopped} keeps, for the same reason: a projector going down with
	 * its context is already said by {@code BoundedContextStopping}, for all of them at once, and
	 * reporting it again per read model would cost the event its meaning — stopped while the context
	 * serving it is up.
	 */
	@Test
	void aProjectorGoingDownWithItsContextReportsNothing ( ) {
		MockReadModel healthy = new MockReadModel("healthy-readmodel", ReadModelStorage.EPHEMERAL);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(healthy).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("one"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(1, healthy.eventCount()));

		received.clear();
		releaseBoundedContext(); // terminates it, as the teardown would

		assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStopping),
				"the context must report its own shutdown: " + received);
		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.ReadModelProjectorStopped),
				"shutdown is reported by BoundedContextStopping, not per read model: " + received);
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private BoundedContextEvent.ReadModelProjectorStarted started ( String readModel ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.ReadModelProjectorStarted.class::isInstance)
					.map(BoundedContextEvent.ReadModelProjectorStarted.class::cast)
					.filter(e -> readModel.equals(e.readModel()))
					.findFirst().orElse(null);
		}
	}

	private BoundedContextEvent.ReadModelProjectorStopped stopped ( String readModel ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.ReadModelProjectorStopped.class::isInstance)
					.map(BoundedContextEvent.ReadModelProjectorStopped.class::cast)
					.filter(e -> readModel.equals(e.readModel()))
					.findFirst().orElse(null);
		}
	}

	/** A read model that cannot project anything — the poison one a processor retires on. */
	static class ThrowingReadModel implements ReadModel<MockDomainEvent> {

		private final String name;

		ThrowingReadModel ( String name ) {
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
			return EventQuery.forEvents(EventTypesFilter.of(List.of(FirstDomainEvent.class)), Tags.none());
		}

		@Override
		public void when ( MockDomainEvent event ) {
			throw new IllegalStateException("this read model cannot project");
		}

	}

}
