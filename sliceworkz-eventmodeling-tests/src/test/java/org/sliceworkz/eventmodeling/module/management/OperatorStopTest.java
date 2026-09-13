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
package org.sliceworkz.eventmodeling.module.management;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.AutomationStartReason;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.LeadershipReleaseReason;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.ProcessorStopReason;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.module.management.OperatorTestComponents.NoopDispatcher;
import org.sliceworkz.eventmodeling.module.management.OperatorTestComponents.NoopTranslator;
import org.sliceworkz.eventmodeling.module.management.OperatorTestComponents.RecordingAutomation;
import org.sliceworkz.eventstore.events.EphemeralEvent;

/**
 * The operator's stop, on the local admin capabilities: a running automation or processor is parked on
 * request, announced as stopped by an operator rather than by a failure, handles nothing meanwhile, and
 * is put back by the same restart a self-stopped one gets. And — the half that makes the stop safe to
 * offer at all — a leader-only processor stopped this way hands its lease to another instance, where a
 * lifecycle stop keeps it: a lease held by a processor that does no work would hold that work off the
 * whole deployment.
 */
public class OperatorStopTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "OperatorStopContext";
	private static final Duration HEARTBEAT = Duration.ofMillis(200);
	private static final Duration TTL = Duration.ofMillis(600);

	private final List<EphemeralEvent<BoundedContextEvent>> observed = Collections.synchronizedList(new ArrayList<>());
	private final List<BoundedContext<?,?,?>> contexts = new ArrayList<>();

	@AfterEach
	void terminateContexts ( ) {
		contexts.forEach(BoundedContext::terminate);
		contexts.clear();
	}

	@Test
	void stoppingARunningAutomationParksItAndAnnouncesTheOperator ( ) {
		RecordingAutomation automation = new RecordingAutomation("op-stop-todo");
		Mock context = start(builder("node-a").with(automation));
		String id = context.automations().get(0).automation();

		assertTrue(context.stopAutomation(id), "a running automation is stopped");
		AutomationStatus stopped = context.automations().get(0);
		assertFalse(stopped.running());
		assertNull(stopped.stoppedBy(), "nothing failed: an operator stop carries no failure");
		assertFalse(context.stopAutomation(id), "stopping a stopped automation does nothing");

		BoundedContextEvent.AutomationStopped announced = await().atMost(Duration.ofSeconds(5)).until(
				() -> events(BoundedContextEvent.AutomationStopped.class).stream().findFirst().orElse(null), e -> e != null);
		assertEquals(ProcessorStopReason.OPERATOR, announced.reason());
		assertNull(announced.failure());
		assertEquals(id, announced.automation());

		// nothing is handled while it is stopped ...
		context.event(new FirstDomainEvent("while-stopped"));
		await().atMost(Duration.ofSeconds(5)).until(() -> automation.todoList().items().contains("while-stopped"));
		sleep(500);
		assertEquals(List.of(), automation.handled(), "a stopped automation handles nothing");

		// ... and the ordinary restart puts it back, picking up what waited
		assertTrue(context.restartAutomation(id));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(List.of("while-stopped"), automation.handled()));
		assertTrue(events(BoundedContextEvent.AutomationStarted.class).stream().anyMatch(e -> e.reason() == AutomationStartReason.RESTART));
		assertTrue(context.automations().get(0).running());
	}

	@Test
	void stoppingAnUnknownAutomationSaysWhichOnesExist ( ) {
		Mock context = start(builder("node-a").with(new RecordingAutomation("op-unknown-todo")));

		IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class, () -> context.stopAutomation("NoSuchAutomation"));
		assertTrue(rejection.getMessage().contains("RecordingAutomation"), rejection.getMessage());
	}

	@Test
	void stoppingAProcessorAnnouncesTheOperatorAndARestartResumesWhereItLeftOff ( ) {
		MockReadModel readModel = new MockReadModel("op-readmodel");
		Mock context = start(builder("node-a").with(readModel));

		assertTrue(context.stopProcessor(ProcessorKind.READ_MODEL, "op-readmodel"));
		ProcessorStatus stopped = status(context, ProcessorKind.READ_MODEL, "op-readmodel");
		assertFalse(stopped.running());
		assertNull(stopped.stoppedBy());
		assertFalse(context.stopProcessor(ProcessorKind.READ_MODEL, "op-readmodel"), "stopping a stopped processor does nothing");

		BoundedContextEvent.ReadModelProjectorStopped announced = await().atMost(Duration.ofSeconds(5)).until(
				() -> events(BoundedContextEvent.ReadModelProjectorStopped.class).stream().findFirst().orElse(null), e -> e != null);
		assertEquals(ProcessorStopReason.OPERATOR, announced.reason());
		assertNull(announced.failure());
		assertNull(announced.failedAt());

		int projectedBefore = readModel.eventCount();
		context.event(new FirstDomainEvent("while-stopped"));
		sleep(500);
		assertEquals(projectedBefore, readModel.eventCount(), "a stopped projector projects nothing");

		assertTrue(context.restartProcessor(ProcessorKind.READ_MODEL, "op-readmodel"));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(projectedBefore + 1, readModel.eventCount(),
				"the restart resumes from where the position left off, so the event appended meanwhile is projected exactly once"));
		assertTrue(status(context, ProcessorKind.READ_MODEL, "op-readmodel").running());
	}

	@Test
	void stoppingATranslatorOrADispatcherAnnouncesTheOperatorAsWell ( ) {
		Mock context = start(builder("node-a").with(new NoopTranslator()).with(new NoopDispatcher()));

		assertTrue(context.stopProcessor(ProcessorKind.TRANSLATOR, "NoopTranslator"));
		assertTrue(context.stopProcessor(ProcessorKind.DISPATCHER, "NoopDispatcher"));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals(1, events(BoundedContextEvent.TranslatorStopped.class).size());
			assertEquals(1, events(BoundedContextEvent.DispatcherStopped.class).size());
		});
		assertEquals(ProcessorStopReason.OPERATOR, events(BoundedContextEvent.TranslatorStopped.class).get(0).reason());
		assertEquals(ProcessorStopReason.OPERATOR, events(BoundedContextEvent.DispatcherStopped.class).get(0).reason());
		assertFalse(status(context, ProcessorKind.TRANSLATOR, "NoopTranslator").running());
		assertFalse(status(context, ProcessorKind.DISPATCHER, "NoopDispatcher").running());

		assertTrue(context.restartProcessor(ProcessorKind.TRANSLATOR, "NoopTranslator"));
		assertTrue(context.restartProcessor(ProcessorKind.DISPATCHER, "NoopDispatcher"));
		assertTrue(status(context, ProcessorKind.TRANSLATOR, "NoopTranslator").running());
		assertTrue(status(context, ProcessorKind.DISPATCHER, "NoopDispatcher").running());
	}

	/**
	 * The reason an operator stop is not simply {@code stop()} on the processor: the leader elector
	 * would keep renewing the lease of a parked leader, and the automation would run nowhere. Stopped
	 * by an operator, the leader hands its lease over and the other instance carries on.
	 */
	@Test
	void anOperatorStoppedLeaderHandsItsLeaseToTheOtherInstance ( ) {
		RecordingAutomation onA = new RecordingAutomation("op-lease-todo");
		RecordingAutomation onB = new RecordingAutomation("op-lease-todo");
		Mock nodeA = start(builder("node-a").with(onA));
		Mock nodeB = start(builder("node-b").with(onB));

		await().atMost(Duration.ofSeconds(10)).until(() -> nodeA.automations().get(0).leader() || nodeB.automations().get(0).leader());
		boolean aLeads = nodeA.automations().get(0).leader();
		Mock leader = aLeads ? nodeA : nodeB;
		Mock standby = aLeads ? nodeB : nodeA;
		RecordingAutomation onStandby = aLeads ? onB : onA;
		String id = leader.automations().get(0).automation();

		assertTrue(leader.stopAutomation(id));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(standby.automations().get(0).leader(),
				"the lease must move to the instance that is still willing"));
		assertTrue(events(BoundedContextEvent.LeadershipReleased.class).stream()
				.anyMatch(e -> e.reason() == LeadershipReleaseReason.PROCESSOR_STOPPED),
				"the release is announced with the processor-stopped reason: " + events(BoundedContextEvent.LeadershipReleased.class));

		leader.event(new FirstDomainEvent("after-the-hand-over"));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(List.of("after-the-hand-over"), onStandby.handled()));

		// restarting the stopped one puts it back in the race, not back in the lead
		assertTrue(leader.restartAutomation(id));
		assertTrue(leader.automations().get(0).running());
		sleep(HEARTBEAT.toMillis() * 3);
		assertTrue(standby.automations().get(0).leader(), "an equal-priority contender never preempts");
	}

	// ---------------------------------------------------------------------------------------------

	private ContextSetup builder ( String node ) {
		return new ContextSetup(BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("operator-stop", node))
				.leadershipIntervals(HEARTBEAT, TTL)
				.listener(observed::add));
	}

	private Mock start ( ContextSetup setup ) {
		Mock context = setup.builder.build();
		contexts.add(context);
		context.start();
		return context;
	}

	private <E extends BoundedContextEvent> List<E> events ( Class<E> type ) {
		synchronized ( observed ) {
			return observed.stream().map(EphemeralEvent::data).filter(type::isInstance).map(type::cast).toList();
		}
	}

	private static ProcessorStatus status ( Mock context, ProcessorKind kind, String name ) {
		return context.processors().stream().filter(s -> s.kind() == kind && s.name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("no status for " + kind + " '" + name + "' in " + context.processors()));
	}

	private static void sleep ( long ms ) {
		try {
			Thread.sleep(ms);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/** A builder with the components to register, so a test reads as what it deploys. */
	private record ContextSetup ( BoundedContextBuilder<Mock> builder ) {

		ContextSetup with ( RecordingAutomation automation ) {
			builder.readmodel(automation.todoList()).eventuallyConsistent();
			builder.automation(automation);
			return this;
		}

		ContextSetup with ( MockReadModel readModel ) {
			builder.readmodel(readModel).eventuallyConsistent();
			return this;
		}

		ContextSetup with ( NoopTranslator translator ) {
			builder.translator(translator);
			return this;
		}

		ContextSetup with ( NoopDispatcher dispatcher ) {
			builder.dispatcher(dispatcher);
			return this;
		}
	}

}
