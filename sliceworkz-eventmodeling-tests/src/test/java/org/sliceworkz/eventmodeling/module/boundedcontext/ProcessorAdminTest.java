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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * The admin surface over projector-driven processors, mirroring {@code AutomationAdminTest}: a
 * processor retired by a permanent failure is visible with what stopped it, and restartable without
 * restarting the whole bounded context — which used to be the only lever there was.
 */
public class ProcessorAdminTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ProcessorAdminBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	/** One status per processor, each under its own kind, with the storage class the events also carry. */
	@Test
	void statusesListEveryKindOfProcessor ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(new MockReadModel("admin-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		builder.translator(new NoopTranslator());
		builder.dispatcher(new NoopDispatcher());
		Mock domain = buildBoundedContext(builder);

		List<ProcessorStatus> statuses = domain.processors();
		assertEquals(3, statuses.size(), "one status per processor: " + statuses);

		ProcessorStatus readModel = statusOf(statuses, ProcessorKind.READ_MODEL, "admin-readmodel");
		assertEquals("MockReadModel", readModel.componentClass());
		assertEquals("ephemeral", readModel.storage());
		assertTrue(readModel.running());
		assertTrue(readModel.leader(), "a processor projected on every instance always leads here");
		assertNull(readModel.stoppedBy());

		ProcessorStatus translator = statusOf(statuses, ProcessorKind.TRANSLATOR, "NoopTranslator");
		assertEquals("shared", translator.storage(), "a translator's bookmark is shared across the deployment");
		assertTrue(translator.running());

		ProcessorStatus dispatcher = statusOf(statuses, ProcessorKind.DISPATCHER, "NoopDispatcher");
		assertEquals("shared", dispatcher.storage());
		assertTrue(dispatcher.running());
	}

	/**
	 * The whole reason the capability exists: a poison event retires a projector, the status says what
	 * stopped it, and once (the operator believes) the cause is dealt with, {@code restartProcessor}
	 * puts it back — the same batch is replayed, so an unaddressed poison event stops it again.
	 */
	@Test
	void aStoppedProjectorReportsWhatStoppedItAndCanBeRestarted ( ) {
		PoisonedReadModel poisoned = new PoisonedReadModel("poisoned-readmodel");

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(poisoned).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		domain.event(new FirstDomainEvent("poison"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertFalse(statusOf(ProcessorKind.READ_MODEL, "poisoned-readmodel").running(),
						"the poison event must retire the processor"));

		ProcessorStatus stopped = statusOf(ProcessorKind.READ_MODEL, "poisoned-readmodel");
		assertNotNull(stopped.stoppedBy(), "what stopped it is the point of the status");
		assertEquals(EventDeserializationException.class.getName(), stopped.stoppedBy().type());
		assertNotNull(stopped.lastFailure(), "the stopping failure is also the most recent one");

		// the operator "fixes" the cause first — here really, so the restart can succeed
		poisoned.poisonous = false;
		int startedBefore = startedCount("poisoned-readmodel");

		assertTrue(domain.restartProcessor(ProcessorKind.READ_MODEL, "poisoned-readmodel"),
				"a stopped processor must be restarted");

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of("poison"), poisoned.applied(),
						"the restart must replay the batch it stopped on — nothing was skipped by the stop"));
		assertTrue(startedCount("poisoned-readmodel") > startedBefore,
				"the restart must be announced like any start: " + received);
		assertNull(statusOf(ProcessorKind.READ_MODEL, "poisoned-readmodel").stoppedBy(),
				"a running processor has no stoppedBy; what stopped it stays as lastFailure");
		assertNotNull(statusOf(ProcessorKind.READ_MODEL, "poisoned-readmodel").lastFailure());
	}

	/** A double-click on a dashboard is harmless. */
	@Test
	void restartingARunningProcessorDoesNothing ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(new MockReadModel("running-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		assertFalse(domain.restartProcessor(ProcessorKind.READ_MODEL, "running-readmodel"));
	}

	/** The rejection names what exists, so a typo is answerable from the exception alone. */
	@Test
	void restartingAnUnknownProcessorSaysWhichOnesExist ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.readmodel(new MockReadModel("known-readmodel", ReadModelStorage.EPHEMERAL)).eventuallyConsistent();
		Mock domain = buildBoundedContext(builder);

		IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
				() -> domain.restartProcessor(ProcessorKind.READ_MODEL, "no-such-readmodel"));
		assertTrue(rejection.getMessage().contains("known-readmodel"),
				"the rejection must name the known processors: " + rejection.getMessage());
		assertTrue(rejection.getMessage().contains("no-such-readmodel"),
				"the rejection must name what was asked for: " + rejection.getMessage());
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private ProcessorStatus statusOf ( ProcessorKind kind, String name ) {
		return statusOf(((Mock) boundedContext).processors(), kind, name);
	}

	private static ProcessorStatus statusOf ( List<ProcessorStatus> statuses, ProcessorKind kind, String name ) {
		return statuses.stream()
				.filter(s -> s.kind() == kind && s.name().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no status for %s '%s' in %s".formatted(kind, name, statuses)));
	}

	private int startedCount ( String readModel ) {
		synchronized ( received ) {
			return (int) received.stream()
					.filter(BoundedContextEvent.ReadModelProjectorStarted.class::isInstance)
					.map(BoundedContextEvent.ReadModelProjectorStarted.class::cast)
					.filter(e -> readModel.equals(e.readModel()))
					.count();
		}
	}

	/** A read model with a poison event — a permanent failure — that can be "fixed" for the restart half. */
	static class PoisonedReadModel implements ReadModel<MockDomainEvent> {

		volatile boolean poisonous = true;
		private final String name;
		private final List<String> applied = Collections.synchronizedList(new ArrayList<>());

		PoisonedReadModel ( String name ) {
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
			if ( poisonous ) {
				throw new EventDeserializationException(EventType.of(event.getClass()), "this event cannot be read");
			}
			applied.add(((FirstDomainEvent) event).value());
		}

		List<String> applied ( ) {
			return List.copyOf(applied);
		}
	}

	static class NoopTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			// no-op for the test
		}
	}

	static class NoopDispatcher implements Dispatcher<MockOutboundEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( MockOutboundEvent event ) {
			// no-op for the test
		}
	}

}
