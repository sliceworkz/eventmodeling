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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.AutomationStartReason;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.InstanceStatusReported;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.InstructionHandled;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.InstructionOutcome;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.ProcessorStopReason;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.management.ManagementInstruction;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.ReportStatus;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StartAutomation;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StartBoundedContext;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StartProcessor;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StopAutomation;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StopBoundedContext;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.StopProcessor;
import org.sliceworkz.eventmodeling.management.ManagementInstruction.Target;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.module.management.OperatorTestComponents.RecordingAutomation;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The management stream end to end: an operator appends a {@link ManagementInstruction}, the
 * instances its target names apply it through their own admin capabilities and answer with an
 * {@code InstructionHandled} carrying the instruction's correlation id, and the instances it does not
 * name stay silent. Two instances live in one JVM here, on one storage, so which one an instruction
 * reaches is observable directly.
 * <p>
 * The one property that is not "it works" is pinned as well: instructions are read from the head at
 * start, so a process coming up does not replay what was said before it existed.
 */
public class ManagementInstructionTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ManagedContext";
	private static final String LOGICAL = "managed";
	private static final Duration HEARTBEAT = Duration.ofMillis(200);
	private static final Duration TTL = Duration.ofMillis(600);

	private EventStore managementStore;
	private EventStream<ManagementInstruction> operator;
	private final Instance operatorInstance = InstanceFactory.determine("operator-console");

	private final List<BoundedContext<?,?,?>> contexts = new ArrayList<>();
	private final Map<String, List<EphemeralEvent<BoundedContextEvent>>> observed = new ConcurrentHashMap<>();

	@BeforeEach
	void openTheManagementStore ( ) {
		// the stream lives on the same storage as the domain here; in production it lives wherever the
		// monitoring store does, which is what every instance and the operator's tooling can reach
		managementStore = EventStoreFactory.get().eventStore(eventStorage());
		operator = managementStore.getEventStream(ManagementInstruction.STREAM, ManagementInstruction.class);
	}

	@AfterEach
	void terminateContexts ( ) {
		contexts.forEach(BoundedContext::terminate);
		contexts.clear();
		managementStore.close();
	}

	@ForEachBackend
	public void anInstructionReachesOnlyTheInstanceItNames ( ) {
		Node nodeA = start("node-a");
		Node nodeB = start("node-b");

		String correlationId = instruct(new StopAutomation(Target.instance(CONTEXT_NAME, LOGICAL, "node-a"), nodeA.automationId()));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertFalse(nodeA.context.automations().get(0).running(),
				"the instruction must stop the automation on the instance it names"));
		assertTrue(nodeB.context.automations().get(0).running(), "and leave the other instance alone");

		EphemeralEvent<BoundedContextEvent> answer = await().atMost(Duration.ofSeconds(5)).until(() -> answersOf("node-a").stream().findFirst().orElse(null), a -> a != null);
		InstructionHandled handled = (InstructionHandled) answer.data();
		assertEquals(InstructionOutcome.APPLIED, handled.outcome());
		assertEquals("StopAutomation", handled.instruction());
		assertEquals(nodeA.automationId(), handled.subject());
		assertEquals(CONTEXT_NAME, handled.boundedContext());
		assertNull(handled.detail());

		// the answer is matchable to the instruction, and says which instance it came from
		assertEquals(correlationId, tag(answer, Tracing.TAG_CORRELATION_ID));
		assertEquals("operator", tag(answer, "x-actor"));
		assertEquals("node-a", tag(answer, "x-instance-physical"));
		assertEquals(LOGICAL, tag(answer, "x-instance-logical"));

		sleep(300);
		assertEquals(List.of(), answersOf("node-b"), "an instance the target does not name says nothing");
	}

	@Test
	void aContextWideTargetReachesEveryInstanceAndTheRestartBringsThemAllBack ( ) {
		Node nodeA = start("node-a");
		Node nodeB = start("node-b");

		instruct(new StopAutomation(Target.boundedContext(CONTEXT_NAME), nodeA.automationId()));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertFalse(nodeA.context.automations().get(0).running());
			assertFalse(nodeB.context.automations().get(0).running());
		});
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals(1, answersOf("node-a").size());
			assertEquals(1, answersOf("node-b").size());
		});

		// stopped everywhere: the work waits
		nodeA.context.event(new FirstDomainEvent("while-stopped"));
		await().atMost(Duration.ofSeconds(5)).until(() -> nodeA.automation.todoList().items().contains("while-stopped")
				&& nodeB.automation.todoList().items().contains("while-stopped"));
		sleep(500);
		assertEquals(List.of(), nodeA.automation.handled());
		assertEquals(List.of(), nodeB.automation.handled());

		instruct(new StartAutomation(Target.boundedContext(CONTEXT_NAME), nodeA.automationId()));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(List.of("while-stopped"),
				Stream.concat(nodeA.automation.handled().stream(), nodeB.automation.handled().stream()).toList(),
				"once restarted, whichever instance leads handles the item, exactly once"));
		assertTrue(events("node-a", BoundedContextEvent.AutomationStarted.class).stream().anyMatch(e -> e.reason() == AutomationStartReason.RESTART));
	}

	@Test
	void anInstructionIssuedBeforeTheContextStartedIsNotReplayed ( ) {
		instruct(new StopAutomation(Target.boundedContext(CONTEXT_NAME), "RecordingAutomation"));

		Node node = start("node-a");

		sleep(500);
		assertTrue(node.context.automations().get(0).running(), "what was said before this process existed is not for it");
		assertEquals(List.of(), answersOf("node-a"));

		// and what is said from now on is
		instruct(new StopAutomation(Target.boundedContext(CONTEXT_NAME), node.automationId()));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertFalse(node.context.automations().get(0).running()));
	}

	@Test
	void anUnknownAutomationIsRejectedNamingTheKnownOnes ( ) {
		Node node = start("node-a");

		instruct(new StopAutomation(Target.boundedContext(CONTEXT_NAME), "NoSuchAutomation"));

		InstructionHandled answer = await().atMost(Duration.ofSeconds(5)).until(() -> handledOn("node-a").stream().findFirst().orElse(null), a -> a != null);
		assertEquals(InstructionOutcome.REJECTED, answer.outcome());
		assertEquals("NoSuchAutomation", answer.subject());
		assertNotNull(answer.detail());
		assertTrue(answer.detail().contains(node.automationId()), "the rejection names what is registered: " + answer.detail());
		assertTrue(node.context.automations().get(0).running());
	}

	@Test
	void startingARunningAutomationIsAnsweredAsNoChange ( ) {
		Node node = start("node-a");

		instruct(new StartAutomation(Target.boundedContext(CONTEXT_NAME), node.automationId()));

		InstructionHandled answer = await().atMost(Duration.ofSeconds(5)).until(() -> handledOn("node-a").stream().findFirst().orElse(null), a -> a != null);
		assertEquals(InstructionOutcome.NO_CHANGE, answer.outcome());
		assertEquals("StartAutomation", answer.instruction());
	}

	@Test
	void aProcessorCanBeStoppedAndStartedByInstruction ( ) {
		Node node = start("node-a");

		instruct(new StopProcessor(Target.boundedContext(CONTEXT_NAME), ProcessorKind.READ_MODEL, node.readModel.readmodelName()));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertFalse(processorRunning(node, node.readModel.readmodelName())));
		InstructionHandled stopped = await().atMost(Duration.ofSeconds(5)).until(() -> handledOn("node-a").stream().findFirst().orElse(null), a -> a != null);
		assertEquals(InstructionOutcome.APPLIED, stopped.outcome());
		assertEquals("READ_MODEL/" + node.readModel.readmodelName(), stopped.subject());
		assertEquals(ProcessorStopReason.OPERATOR, events("node-a", BoundedContextEvent.ReadModelProjectorStopped.class).get(0).reason());

		instruct(new StartProcessor(Target.boundedContext(CONTEXT_NAME), ProcessorKind.READ_MODEL, node.readModel.readmodelName()));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(processorRunning(node, node.readModel.readmodelName())));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(2, handledOn("node-a").size()));
		assertEquals(InstructionOutcome.APPLIED, handledOn("node-a").get(1).outcome());
	}

	@Test
	void theWholeContextCanBeStoppedAndStartedByInstruction ( ) {
		Node node = start("node-a");

		instruct(new StopBoundedContext(Target.instance(CONTEXT_NAME, LOGICAL, "node-a")));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertFalse(node.context.automations().get(0).running(),
				"a stopped context has parked its processors"));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(1, handledOn("node-a").size()));
		assertEquals(InstructionOutcome.APPLIED, handledOn("node-a").get(0).outcome());
		assertEquals(CONTEXT_NAME, handledOn("node-a").get(0).subject());

		// a stopped context keeps listening: that is what makes the next two answerable at all
		instruct(new StopBoundedContext(Target.instance(CONTEXT_NAME, LOGICAL, "node-a")));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(2, handledOn("node-a").size()));
		assertEquals(InstructionOutcome.NO_CHANGE, handledOn("node-a").get(1).outcome());

		instruct(new StartBoundedContext(Target.instance(CONTEXT_NAME, LOGICAL, "node-a")));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(3, handledOn("node-a").size()));
		assertEquals(InstructionOutcome.APPLIED, handledOn("node-a").get(2).outcome());
		assertTrue(node.context.automations().get(0).running());

		node.context.event(new FirstDomainEvent("after-restart"));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(List.of("after-restart"), node.automation.handled()));
	}

	@Test
	void reportStatusAnswersWithWhatTheAdminCapabilitiesReportLocally ( ) {
		Node node = start("node-a");

		instruct(new ReportStatus(Target.any()));

		InstanceStatusReported report = await().atMost(Duration.ofSeconds(5)).until(
				() -> events("node-a", InstanceStatusReported.class).stream().findFirst().orElse(null), r -> r != null);
		assertEquals(node.context.automations(), report.automations());
		assertEquals(node.context.processors(), report.processors());
		assertEquals(1, report.automations().size());
		assertTrue(report.processors().stream().anyMatch(p -> p.name().equals(node.readModel.readmodelName())));
		assertEquals(InstructionOutcome.APPLIED, handledOn("node-a").get(0).outcome());
	}

	/**
	 * The answers are meant to be persisted by a monitoring listener and read back by another process,
	 * so the new event shapes — including the statuses a report carries — have to survive the store.
	 */
	@Test
	void answersSurviveTheRoundTripThroughAMonitoringStream ( ) {
		EventStream<BoundedContextEvent> monitoring = managementStore.getEventStream(
				EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		Node node = start("node-a", builder -> builder.listener(new StreamAppendingBoundedContextListener(monitoring)));

		instruct(new ReportStatus(Target.any()));
		instruct(new StopAutomation(Target.any(), node.automationId()));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			List<BoundedContextEvent> stored = monitoring.query(EventQuery.matchAll()).map(Event::data).toList();
			assertTrue(stored.stream().anyMatch(e -> e instanceof InstanceStatusReported r && r.automations().size() == 1 && !r.processors().isEmpty()),
					"the status report must read back with its statuses: " + stored);
			assertEquals(2, stored.stream().filter(InstructionHandled.class::isInstance).count(), "both answers must read back: " + stored);
			assertTrue(stored.stream().anyMatch(e -> e instanceof BoundedContextEvent.AutomationStopped s
					&& s.reason() == ProcessorStopReason.OPERATOR && s.failure() == null),
					"the operator stop must read back with its reason: " + stored);
		});
	}

	// ---------------------------------------------------------------------------------------------

	private String instruct ( ManagementInstruction instruction ) {
		Tracing tracing = Tracing.init(operatorInstance).actor("operator").channel("test");
		operator.append(AppendCriteria.none(), tracing.<ManagementInstruction>storeOn(EphemeralEvent.of(instruction, Tags.none())));
		return tracing.correlationId();
	}

	private Node start ( String physical ) {
		return start(physical, builder -> builder);
	}

	private Node start ( String physical, java.util.function.UnaryOperator<BoundedContextBuilder<Mock>> customize ) {
		List<EphemeralEvent<BoundedContextEvent>> events = observed.computeIfAbsent(physical, p -> new CopyOnWriteArrayList<>());
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine(LOGICAL, physical))
				.leadershipIntervals(HEARTBEAT, TTL)
				.listener(events::add)
				// a stream of its own per context, as the builder asks: terminate() closes it
				.management(managementStore.getEventStream(ManagementInstruction.STREAM, ManagementInstruction.class));
		builder = customize.apply(builder);
		RecordingAutomation automation = new RecordingAutomation("managed-todo");
		MockReadModel readModel = new MockReadModel("managed-readmodel");
		builder.readmodel(automation.todoList()).eventuallyConsistent();
		builder.automation(automation);
		builder.readmodel(readModel).eventuallyConsistent();
		Mock context = builder.build();
		contexts.add(context);
		context.start();
		return new Node(context, automation, readModel);
	}

	private List<EphemeralEvent<BoundedContextEvent>> answersOf ( String physical ) {
		return observed.getOrDefault(physical, List.of()).stream().filter(e -> e.data() instanceof InstructionHandled).toList();
	}

	private List<InstructionHandled> handledOn ( String physical ) {
		return events(physical, InstructionHandled.class);
	}

	private <E extends BoundedContextEvent> List<E> events ( String physical, Class<E> type ) {
		return observed.getOrDefault(physical, List.of()).stream().map(EphemeralEvent::data).filter(type::isInstance).map(type::cast).toList();
	}

	private static boolean processorRunning ( Node node, String name ) {
		return node.context.processors().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().running();
	}

	private static String tag ( EphemeralEvent<?> event, String key ) {
		return event.tags().tag(key).map(Tag::value).orElse(null);
	}

	private static void sleep ( long ms ) {
		try {
			Thread.sleep(ms);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	private record Node ( Mock context, RecordingAutomation automation, MockReadModel readModel ) {

		String automationId ( ) {
			return context.automations().get(0).automation();
		}
	}

}
