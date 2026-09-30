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
package org.sliceworkz.eventmodeling.module.ports;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.CorrelatedTodoItem;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallRejected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCalled;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallsSummarized;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.events.TracingScope;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.ports.CallGatewayCommand;
import org.sliceworkz.eventmodeling.mock.ports.CallGatewayFeatureSlice;
import org.sliceworkz.eventmodeling.mock.ports.GatewayLookupReadModel;
import org.sliceworkz.eventmodeling.mock.ports.GatewayPort;
import org.sliceworkz.eventmodeling.mock.ports.GatewayPort.DeclinedException;
import org.sliceworkz.eventmodeling.mock.ports.GatewayPort.GatewayDownException;
import org.sliceworkz.eventmodeling.mock.ports.GatewayPort.HardDeclinedException;
import org.sliceworkz.eventmodeling.mock.ports.GatewayPort.NotFoundException;
import org.sliceworkz.eventmodeling.mock.ports.ScriptedGateway;
import org.sliceworkz.eventmodeling.mock.ports.ScriptedGateway.GatewayError;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.ports.PortCaller;
import org.sliceworkz.eventmodeling.ports.PortLatencyBuckets;
import org.sliceworkz.eventmodeling.ports.PortMonitoring;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A monitored port: every way a call can end reaches the caller exactly as the adapter ended it, and is
 * reported as exactly one of {@code PortCalled} (returned), {@code PortCallRejected} (a business exception)
 * or {@code PortCallFailed} (anything else) — or counted in a {@code PortCallsSummarized} for a summarized
 * port. The caller is the component whose code made the call, with its slice and its flow.
 * <p>
 * Framework behaviour rather than storage behaviour, so plain {@code @Test}s against the in-memory store.
 */
public class MonitoredPortTest extends AbstractMockDomainTest {

	private static final String CONTEXT = "UnitTestBoundedContext";

	private final List<EphemeralEvent<BoundedContextEvent>> observed = Collections.synchronizedList(new ArrayList<>());
	private ScriptedGateway adapter;

	@BeforeEach
	void freshAdapter ( ) {
		adapter = new ScriptedGateway();
		observed.clear();
		CallGatewayFeatureSlice.handedOut = null;
		CallGatewayFeatureSlice.startedWith = null;
		CallGatewayFeatureSlice.startedOn = null;
	}

	// ════════════════════════════════════════════════════════════════════
	// how a call ends, and what it is reported as
	// ════════════════════════════════════════════════════════════════════

	@Test
	void aCallThatReturnsHandsBackTheAnswerAndIsReportedAsPortCalled ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		assertEquals("answer to life", gateway.answer("life"));

		assertEquals(1, adapter.calls.get(), "the call reached the adapter once");
		PortCalled called = only(PortCalled.class);
		assertEquals(CONTEXT, called.boundedContext());
		assertEquals("GatewayPort", called.port());
		assertNull(called.qualification(), "the default qualification is reported as none");
		assertEquals("answer", called.method());
		assertEquals(PortCaller.UNATTRIBUTED, called.caller(), "called from the test thread, outside every component");
		assertNull(called.slice());
		assertTrue(called.durationMicros() >= 0);
		assertNone(PortCallRejected.class);
		assertNone(PortCallFailed.class);
	}

	@Test
	void aNullAnswerAndADefaultMethodPassThroughAndAreReported ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		assertNull(gateway.nothing());
		assertEquals("hello bob", gateway.greet("bob"));

		assertEquals(List.of("nothing", "greet"), all(PortCalled.class).stream().map(PortCalled::method).toList());
	}

	@Test
	void aBusinessExceptionReachesTheCallerUnchangedAndIsReportedAsRejected ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		NotFoundException thrown = assertThrows(NotFoundException.class, () -> gateway.refuse("no such customer"));
		assertEquals("no such customer", thrown.getMessage());

		PortCallRejected rejected = only(PortCallRejected.class);
		assertEquals("refuse", rejected.method());
		assertEquals(NotFoundException.class.getName(), rejected.exceptionType());
		assertEquals("no such customer", rejected.reason());
		assertNone(PortCallFailed.class);
		assertNone(PortCalled.class);
	}

	@Test
	void aDeclaredBusinessExceptionAndItsSubtypesAreRejectedNotFailed ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall().businessExceptions(DeclinedException.class));

		assertThrows(DeclinedException.class, () -> gateway.decline("limit reached"));
		assertThrows(HardDeclinedException.class, () -> gateway.declineHard("account blocked"));

		List<PortCallRejected> rejected = all(PortCallRejected.class);
		assertEquals(List.of(DeclinedException.class.getName(), HardDeclinedException.class.getName()),
				rejected.stream().map(PortCallRejected::exceptionType).toList());
		assertNone(PortCallFailed.class);
	}

	@Test
	void anUndeclaredThirdPartyExceptionIsAFailure ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		assertThrows(DeclinedException.class, () -> gateway.decline("limit reached"));

		PortCallFailed failed = only(PortCallFailed.class);
		assertEquals(DeclinedException.class.getName(), failed.failure().type(),
				"only a BusinessException, or a declared type, is a business answer");
		assertNone(PortCallRejected.class);
	}

	@Test
	void aRuntimeFailureReachesTheCallerUnchangedAndIsReportedWithItsStackTrace ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> gateway.breakDown("adapter bug"));
		assertEquals("adapter bug", thrown.getMessage());

		PortCallFailed failed = only(PortCallFailed.class);
		assertEquals("breakDown", failed.method());
		assertEquals(IllegalStateException.class.getName(), failed.failure().type());
		assertEquals("adapter bug", failed.failure().message());
		assertFalse(failed.summarized(), "a per-call failure is counted nowhere else");
		assertTrue(failed.failure().stackTrace().contains("ScriptedGateway.breakDown"),
				"the stack trace is the adapter's own, not the proxy's");
		assertNone(PortCallRejected.class);
	}

	@Test
	void aPortUnavailableExceptionIsAlwaysAFailure ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall().businessExceptions(DeclinedException.class));

		assertThrows(GatewayDownException.class, gateway::unreachable);

		assertEquals(GatewayDownException.class.getName(), only(PortCallFailed.class).failure().type());
		assertNone(PortCallRejected.class);
	}

	@Test
	void aDeclaredCheckedExceptionReachesTheCallerAsItselfNotWrapped ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		IOException thrown = assertThrows(IOException.class, gateway::readFile,
				"not an UndeclaredThrowableException, nor an InvocationTargetException");
		assertEquals("disk gone", thrown.getMessage());

		assertEquals(IOException.class.getName(), only(PortCallFailed.class).failure().type());
	}

	@Test
	void anErrorReachesTheCallerAndIsReportedAsAFailure ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		assertThrows(GatewayError.class, gateway::crash);

		assertEquals(GatewayError.class.getName(), only(PortCallFailed.class).failure().type());
	}

	@Test
	void everyCallIsReportedAsExactlyOneOutcome ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		gateway.answer("a");
		assertThrows(NotFoundException.class, () -> gateway.refuse("b"));
		assertThrows(IllegalStateException.class, () -> gateway.breakDown("c"));
		gateway.answer("d");

		assertEquals(2, all(PortCalled.class).size());
		assertEquals(1, all(PortCallRejected.class).size());
		assertEquals(1, all(PortCallFailed.class).size());
		assertEquals(4, adapter.calls.get());
	}

	@Test
	void equalsHashCodeAndToStringAreTheProxysOwnAndNotReported ( ) {
		GatewayPort gateway = monitoredGateway(PortMonitoring.perCall());

		assertTrue(gateway.equals(gateway));
		assertFalse(gateway.equals(adapter), "the proxy is not equal to the adapter behind it");
		assertEquals(System.identityHashCode(gateway), gateway.hashCode());
		assertTrue(gateway.toString().contains("GatewayPort"));

		assertEquals(0, adapter.calls.get());
		assertNoPortEvents();
	}

	// ════════════════════════════════════════════════════════════════════
	// the binding
	// ════════════════════════════════════════════════════════════════════

	@Test
	void anUnmonitoredPortIsTheAdapterItselfAndReportsNothing ( ) {
		Mock domain = buildBoundedContext(baseBuilder().adapter(adapter).forPort(GatewayPort.class));

		GatewayPort gateway = domain.port(GatewayPort.class);
		assertSame(adapter, gateway);
		gateway.answer("x");
		assertThrows(IllegalStateException.class, () -> gateway.breakDown("y"));

		assertNoPortEvents();
	}

	@Test
	void theSliceIsHandedTheSameProxyTheRunningContextReportsOn ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));

		assertNotNull(CallGatewayFeatureSlice.handedOut);
		assertSame(domain.port(GatewayPort.class), CallGatewayFeatureSlice.handedOut);
		assertNotEquals(adapter, CallGatewayFeatureSlice.handedOut);

		CallGatewayFeatureSlice.handedOut.answer("from the slice's reference");
		assertEquals(1, all(PortCalled.class).size(),
				"the proxy handed out while configuring reports once the context is built");
	}

	@Test
	void aQualifiedBindingIsReportedUnderItsQualification ( ) {
		Mock domain = buildBoundedContext(baseBuilder().listener(observed::add)
				.adapter(adapter).monitored().forPort(GatewayPort.class, "backup"));

		domain.port(GatewayPort.class, "backup").answer("q");

		assertEquals("backup", only(PortCalled.class).qualification());
	}

	@Test
	void onlyAnInterfaceCanBeMonitored ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().adapter(adapter).monitored().forPort(ScriptedGateway.class));
		assertTrue(refused.getMessage().contains("only an interface"), refused.getMessage());
	}

	@Test
	void aSummarizedMethodThePortDoesNotDeclareIsRefused ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> baseBuilder().adapter(adapter)
						.monitored(PortMonitoring.perCall().summarizing(Duration.ofMinutes(1), "answr"))
						.forPort(GatewayPort.class));
		assertTrue(refused.getMessage().contains("answr"), refused.getMessage());
	}

	@Test
	void monitoredWithNullIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> baseBuilder().adapter(adapter).monitored(null));
	}

	@Test
	void theStartingEventListsEveryBindingWithTheSlicesThatAskedForIt ( ) {
		buildBoundedContext(slicedBuilder(PortMonitoring.perCall())
				.adapter(new ScriptedGateway()).forPort(GatewayPort.class, "unmonitored"));

		BoundedContextEvent.BoundedContextStarting starting = only(BoundedContextEvent.BoundedContextStarting.class);
		assertNotNull(starting.ports());
		BoundedContextEvent.PortBinding monitored = starting.ports().stream().filter(p -> p.qualification() == null).findFirst().orElseThrow();
		assertEquals("GatewayPort", monitored.port());
		assertEquals(GatewayPort.class.getName(), monitored.portType());
		assertEquals(ScriptedGateway.class.getName(), monitored.adapter());
		assertTrue(monitored.monitored());
		assertEquals("per call", monitored.monitoring());
		assertEquals(java.util.Set.of("CallGateway"), monitored.slices());

		BoundedContextEvent.PortBinding unmonitored = starting.ports().stream().filter(p -> "unmonitored".equals(p.qualification())).findFirst().orElseThrow();
		assertFalse(unmonitored.monitored());
		assertNull(unmonitored.monitoring());
		assertTrue(unmonitored.slices().isEmpty());
	}

	// ════════════════════════════════════════════════════════════════════
	// who called
	// ════════════════════════════════════════════════════════════════════

	@Test
	void aCallFromACommandNamesTheCommandItsSliceAndItsFlow ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort gateway = domain.port(GatewayPort.class);

		domain.execute(new CallGatewayCommand(gateway, g -> g.answer("q")),
				Tracing.actorAndChannel("alice", "api").correlationId("flow-port"));

		EphemeralEvent<BoundedContextEvent> event = onlyEvent(PortCalled.class);
		PortCalled called = (PortCalled) event.data();
		assertEquals(PortCaller.command("CallGateway"), called.caller());
		assertNotNull(called.slice());
		assertEquals("CallGateway", called.slice().name(), "the caller's slice, found by the command's package");
		assertEquals("Ports", called.slice().chapter());
		assertEquals("flow-port", tag(event, Tracing.TAG_CORRELATION_ID));
		assertEquals("alice", tag(event, "x-actor"));
		assertEquals("CallGateway", tag(event, "x-command"));
		assertNotNull(tag(event, "x-instance-logical"), "the instance travels on the tags, as on every kernel event");
		assertNotNull(tag(event, "x-instance-process"));
	}

	@Test
	void aCallThroughThePortASliceTookWhenStartedIsThatSlicesOnARequestThread ( ) throws InterruptedException {
		buildBoundedContext(slicedBuilder(PortMonitoring.perCall().businessExceptions(DeclinedException.class)));
		GatewayPort startedWith = CallGatewayFeatureSlice.startedWith;
		assertNotNull(startedWith, "the slice took the port while it was started");

		// the way a REST endpoint wired in startCommand calls it: on a thread the framework did not start
		List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
		Thread request = Thread.ofVirtual().start(( ) -> {
			startedWith.answer("from an endpoint");
			try {
				startedWith.decline("no");
			} catch ( Throwable t ) {
				thrown.add(t);
			}
		});
		request.join();
		assertEquals(1, thrown.size());
		assertInstanceOf(DeclinedException.class, thrown.get(0));

		PortCalled called = only(PortCalled.class);
		assertEquals(PortCaller.slice("CallGateway"), called.caller());
		assertNotNull(called.slice());
		assertEquals("CallGateway", called.slice().name());
		PortCallRejected rejected = only(PortCallRejected.class);
		assertEquals(PortCaller.slice("CallGateway"), rejected.caller());
		assertEquals("CallGateway", rejected.slice().name());
	}

	@Test
	void aPortLookedUpPerRequestThroughTheContextASliceWasHandedIsThatSlicesToo ( ) throws InterruptedException {
		buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		Mock startedOn = CallGatewayFeatureSlice.startedOn;

		// an endpoint that keeps the context and asks it for the port on every request
		Thread request = Thread.ofVirtual().start(( ) -> startedOn.port(GatewayPort.class).answer("per request"));
		request.join();

		assertEquals(PortCaller.slice("CallGateway"), only(PortCalled.class).caller());
		assertSame(CallGatewayFeatureSlice.startedWith, startedOn.port(GatewayPort.class), "one proxy per slice and binding");
	}

	@Test
	void aRequestsBoundTracingIsTheFlowOfItsPortCallsAndOfTheCommandItExecutes ( ) throws InterruptedException {
		buildBoundedContext(slicedBuilder(PortMonitoring.perCall().businessExceptions(DeclinedException.class)));
		GatewayPort startedWith = CallGatewayFeatureSlice.startedWith;
		Mock startedOn = CallGatewayFeatureSlice.startedOn;

		// an endpoint as the modeler's are: check access through the port, then execute -- all on one request
		// thread whose edge bound the request's tracing upfront
		Thread request = Thread.ofVirtual().start(( ) -> {
			try ( TracingScope scope = TracingScope.bind(Tracing.actorAndChannel("alice", "api").correlationId("request-flow")) ) {
				startedWith.answer("may alice?");
				try {
					startedWith.decline("no");
				} catch ( DeclinedException expected ) {
					// the port's answer
				}
				startedOn.execute(new CallGatewayCommand(startedWith, g -> g.answer("q")));
			}
		});
		request.join();

		List<EphemeralEvent<BoundedContextEvent>> calls = observedOf(PortCalled.class);
		assertEquals(2, calls.size(), "the endpoint's own call and the command's");
		for ( EphemeralEvent<BoundedContextEvent> call : calls ) {
			assertEquals("request-flow", tag(call, Tracing.TAG_CORRELATION_ID), "one request, one flow: " + call.data());
			assertEquals("alice", tag(call, "x-actor"));
		}
		assertEquals(PortCaller.slice("CallGateway"), ((PortCalled) calls.get(0).data()).caller(), "still the slice's call");
		assertEquals("request-flow", tag(onlyEvent(PortCallRejected.class), Tracing.TAG_CORRELATION_ID));
		assertEquals("request-flow", tag(onlyEvent(BoundedContextEvent.CommandExecuted.class), Tracing.TAG_CORRELATION_ID),
				"a command executed without a tracing takes the request's");
		assertEquals("api", tag(onlyEvent(BoundedContextEvent.CommandExecuted.class), "x-channel"));
	}

	@Test
	void anExplicitTracingWinsOverTheBoundOne ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));

		try ( TracingScope scope = TracingScope.bind(Tracing.actorAndChannel("alice", "api").correlationId("request-flow")) ) {
			domain.execute(new CallGatewayCommand(domain.port(GatewayPort.class), g -> g.answer("q")),
					Tracing.actorAndChannel("bob", "batch").correlationId("explicit-flow"));
		}

		EphemeralEvent<BoundedContextEvent> call = onlyEvent(PortCalled.class);
		assertEquals("explicit-flow", tag(call, Tracing.TAG_CORRELATION_ID), "the command's own tracing");
		assertEquals("bob", tag(call, "x-actor"));
	}

	@Test
	void withoutABoundTracingEachEndpointCallIsAFlowOfItsOwn ( ) throws InterruptedException {
		buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort startedWith = CallGatewayFeatureSlice.startedWith;

		Thread request = Thread.ofVirtual().start(( ) -> {
			startedWith.answer("one");
			startedWith.answer("two");
		});
		request.join();

		List<EphemeralEvent<BoundedContextEvent>> calls = observedOf(PortCalled.class);
		assertEquals(2, calls.size());
		assertNotEquals(tag(calls.get(0), Tracing.TAG_CORRELATION_ID), tag(calls.get(1), Tracing.TAG_CORRELATION_ID),
				"nothing ties two calls together that no edge bound a tracing for");
	}

	@Test
	void theContextASliceIsHandedIsTheContextInEveryOtherRespect ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		Mock startedOn = CallGatewayFeatureSlice.startedOn;

		assertNotSame(domain, startedOn);
		startedOn.execute(new CallGatewayCommand(domain.port(GatewayPort.class), g -> g.answer("q")));
		assertEquals(PortCaller.command("CallGateway"), only(PortCalled.class).caller(), "executed on the context itself");
		assertThrows(IllegalStateException.class, ( ) -> startedOn.port(GatewayPort.class, "no such qualification"),
				"a missing binding is refused as it is on the context");
	}

	@Test
	void aComponentRunningOnTheThreadStillWinsOverTheSliceThatTookThePort ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));

		domain.execute(new CallGatewayCommand(CallGatewayFeatureSlice.startedWith, g -> g.answer("q")));

		assertEquals(PortCaller.command("CallGateway"), only(PortCalled.class).caller());
	}

	@Test
	void aPortTakenFromTheBuiltContextOutsideAStartIsUnattributed ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));

		domain.port(GatewayPort.class).answer("from application code");

		assertEquals(PortCaller.UNATTRIBUTED, only(PortCalled.class).caller());
		assertNotSame(domain.port(GatewayPort.class), CallGatewayFeatureSlice.startedWith);
	}

	@Test
	void anUnmonitoredPortASliceTakesWhenStartedIsTheAdapterItself ( ) {
		buildBoundedContext(baseBuilder().listener(observed::add).adapter(adapter).forPort(GatewayPort.class)
				.features().rootPackage(CallGatewayFeatureSlice.class.getPackage()).done());

		assertSame(adapter, CallGatewayFeatureSlice.startedWith);
	}

	@Test
	void aBusinessExceptionFromAPortRejectsTheCommandToo ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort gateway = domain.port(GatewayPort.class);

		assertThrows(NotFoundException.class, () -> domain.execute(new CallGatewayCommand(gateway, g -> g.refuse("unknown"))));

		assertEquals(PortCaller.command("CallGateway"), only(PortCallRejected.class).caller());
		assertEquals(1, all(BoundedContextEvent.CommandRejected.class).size(),
				"the port's no and the command's no agree");
		assertNone(BoundedContextEvent.CommandFailed.class);
	}

	@Test
	void aFailureFromAPortFailsTheCommandToo ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort gateway = domain.port(GatewayPort.class);

		assertThrows(GatewayDownException.class, () -> domain.execute(new CallGatewayCommand(gateway, GatewayPort::unreachable)));

		assertEquals(PortCaller.command("CallGateway"), only(PortCallFailed.class).caller());
		assertEquals(1, all(BoundedContextEvent.CommandFailed.class).size());
	}

	@Test
	void aCallFromALiveReadModelNamesTheReadModel ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort gateway = domain.port(GatewayPort.class);
		domain.execute(new CallGatewayCommand(gateway, g -> "seed"));
		observed.clear();

		GatewayLookupReadModel lookup = domain.read(GatewayLookupReadModel.class, gateway);

		assertEquals(List.of("answer to seed"), lookup.answers());
		assertEquals(PortCaller.readModel("GatewayLookupReadModel"), only(PortCalled.class).caller());
	}

	@Test
	void aReadInsideACommandIsTheReadModelsAndTheCommandsAgainAfterIt ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall()));
		GatewayPort gateway = domain.port(GatewayPort.class);
		domain.execute(new CallGatewayCommand(gateway, g -> "seed"));
		observed.clear();

		domain.execute(new ReadThenCallCommand(gateway));

		List<PortCalled> calls = all(PortCalled.class);
		assertEquals(2, calls.size());
		assertEquals(PortCaller.readModel("GatewayLookupReadModel"), calls.get(0).caller());
		assertEquals(PortCaller.command("ReadThenCall"), calls.get(1).caller(), "the nested scope was restored");
	}

	@Test
	void aCallFromATranslatorNamesTheTranslator ( ) {
		BoundedContextBuilder<Mock> builder = slicedBuilder(PortMonitoring.perCall());
		builder.translator(new LookupTranslator(builder.port(GatewayPort.class)));
		Mock domain = buildBoundedContext(builder);
		observed.clear();

		domain.translate(new SomeInboundEvent("hi"), Tracing.actorAndChannel("bob", "api").correlationId("flow-t"));

		EphemeralEvent<BoundedContextEvent> event = onlyEvent(PortCalled.class);
		assertEquals(PortCaller.translator("LookupTranslator"), ((PortCalled) event.data()).caller());
		assertEquals("flow-t", tag(event, Tracing.TAG_CORRELATION_ID));
	}

	@Test
	void aCallFromAnAutomationNamesTheAutomationAndTheItemsFlow ( ) {
		ItemsToLookUp todoList = new ItemsToLookUp();
		var builder = slicedBuilder(PortMonitoring.perCall());
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new LookUpAutomation(todoList, builder.port(GatewayPort.class)));
		Mock domain = buildBoundedContext(builder);

		domain.execute(new CallGatewayCommand(domain.port(GatewayPort.class), g -> "item-1"),
				Tracing.actorAndChannel("carol", "api").correlationId("flow-a"));

		await().atMost(Duration.ofSeconds(30)).until(() -> all(PortCalled.class).stream()
				.anyMatch(c -> PortCaller.KIND_AUTOMATION.equals(c.caller().kind())));
		EphemeralEvent<BoundedContextEvent> event = observedOf(PortCalled.class).stream()
				.filter(e -> PortCaller.KIND_AUTOMATION.equals(((PortCalled) e.data()).caller().kind()))
				.findFirst().orElseThrow();
		assertEquals(PortCaller.automation("LookUpAutomation"), ((PortCalled) event.data()).caller());
		assertEquals("flow-a", tag(event, Tracing.TAG_CORRELATION_ID), "the flow of the event that put the item on the list");
	}

	// ════════════════════════════════════════════════════════════════════
	// summarized
	// ════════════════════════════════════════════════════════════════════

	@Test
	void aSummarizedPortCountsItsCallsAndEmitsThemWhenTheContextStops ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1)).businessExceptions(DeclinedException.class)));
		GatewayPort gateway = domain.port(GatewayPort.class);

		gateway.answer("a");
		gateway.answer("b");
		assertThrows(DeclinedException.class, () -> gateway.decline("c"));
		assertThrows(NotFoundException.class, () -> gateway.refuse("d"));
		assertNone(PortCalled.class);
		assertNone(PortCallRejected.class);
		assertNone(PortCallsSummarized.class);

		domain.stop();

		List<PortCallsSummarized> summaries = all(PortCallsSummarized.class);
		PortCallsSummarized answers = summaryOf(summaries, "answer");
		assertEquals(2, answers.called());
		assertEquals(0, answers.rejected());
		assertEquals(0, answers.failed());
		assertEquals(2, answers.calls());
		assertEquals(PortCaller.UNATTRIBUTED, answers.caller());
		assertEquals(PortLatencyBuckets.COUNT, answers.latencyBuckets().size());
		assertEquals(2L, answers.latencyBuckets().stream().mapToLong(Long::longValue).sum(), "every call is in a bucket");
		assertTrue(answers.maxMicros() <= answers.totalMicros());
		assertEquals(0, answers.windowStart().toEpochMilli() % Duration.ofHours(1).toMillis(), "windows are aligned to the wall clock");
		assertFalse(answers.windowEnd().isBefore(answers.windowStart()));
		assertEquals(1, summaryOf(summaries, "decline").rejected());
		assertEquals(1, summaryOf(summaries, "refuse").rejected());
	}

	@Test
	void aSummarizedPortStillReportsTheFirstFailureOfEachTypeOnItsOwn ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1))));
		GatewayPort gateway = domain.port(GatewayPort.class);

		assertThrows(IllegalStateException.class, () -> gateway.breakDown("first"));
		assertThrows(IllegalStateException.class, () -> gateway.breakDown("second"));
		assertThrows(GatewayDownException.class, gateway::unreachable);

		List<PortCallFailed> failures = all(PortCallFailed.class);
		assertEquals(2, failures.size(), "one per exception type per method, the repeat only counted");
		assertEquals("first", failures.get(0).failure().message());
		assertTrue(failures.stream().allMatch(PortCallFailed::summarized), "counted in the summary too, and saying so");
		assertEquals(GatewayDownException.class.getName(), failures.get(1).failure().type());

		domain.stop();
		assertEquals(2, summaryOf(all(PortCallsSummarized.class), "breakDown").failed(), "every failure is counted");
		assertEquals(1, summaryOf(all(PortCallsSummarized.class), "unreachable").failed());
	}

	@Test
	void summariesAreKeptApartPerCaller ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1))));
		GatewayPort gateway = domain.port(GatewayPort.class);

		gateway.answer("from the test");
		domain.execute(new CallGatewayCommand(gateway, g -> g.answer("from a command")));
		domain.stop();

		List<PortCallsSummarized> summaries = all(PortCallsSummarized.class);
		assertEquals(2, summaries.size());
		PortCallsSummarized fromCommand = summaries.stream().filter(s -> PortCaller.KIND_COMMAND.equals(s.caller().kind())).findFirst().orElseThrow();
		assertEquals(1, fromCommand.called());
		assertEquals("CallGateway", fromCommand.slice().name());
	}

	@Test
	void summarizingOneMethodLeavesTheOthersPerCall ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.perCall().summarizing(Duration.ofHours(1), "answer")));
		GatewayPort gateway = domain.port(GatewayPort.class);

		gateway.answer("hot");
		gateway.answer("hot again");
		gateway.greet("rare");

		assertEquals(List.of("greet"), all(PortCalled.class).stream().map(PortCalled::method).toList());
		domain.stop();
		assertEquals(2, summaryOf(all(PortCallsSummarized.class), "answer").called());
	}

	@Test
	void aStoppedContextWithNothingCountedEmitsNoSummary ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1))));
		domain.stop();
		assertNone(PortCallsSummarized.class);
	}

	@Test
	void theLastWindowIsEmittedOnTerminateToo ( ) {
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1))));
		domain.port(GatewayPort.class).answer("before terminate");

		domain.terminate();

		assertEquals(1, summaryOf(all(PortCallsSummarized.class), "answer").called());
		int summaryAt = indexOf(PortCallsSummarized.class);
		int stoppedAt = indexOf(BoundedContextEvent.BoundedContextStopped.class);
		assertTrue(summaryAt < stoppedAt, "emitted before the context says it stopped");
	}

	// ════════════════════════════════════════════════════════════════════
	// the observer, and reporting that fails
	// ════════════════════════════════════════════════════════════════════

	@Test
	void theObserverSeesEveryCallNestedInTheOperationThatMadeIt ( ) {
		RecordingBoundedContextObserver recorder = new RecordingBoundedContextObserver();
		Mock domain = buildBoundedContext(slicedBuilder(PortMonitoring.summarized(Duration.ofHours(1))).observer(recorder));
		GatewayPort gateway = domain.port(GatewayPort.class);

		domain.execute(new CallGatewayCommand(gateway, g -> g.answer("q")));
		assertThrows(NotFoundException.class, () -> gateway.refuse("r"));
		assertThrows(IllegalStateException.class, () -> gateway.breakDown("f"));

		List<RecordingBoundedContextObserver.Recording> calls = recorder.recordings(Observation.PortCall.class);
		assertEquals(3, calls.size(), "every call, even on a summarized port");

		RecordingBoundedContextObserver.Recording fromCommand = calls.get(0);
		assertInstanceOf(Outcome.PortReturned.class, fromCommand.outcome().orElseThrow());
		assertEquals(PortCaller.command("CallGateway"), fromCommand.observation(Observation.PortCall.class).caller());
		assertInstanceOf(Observation.CommandExecution.class, fromCommand.parent().orElseThrow().observation(),
				"a tracer gets the port call as a span inside the command");

		Outcome.PortRejected rejected = calls.get(1).outcome(Outcome.PortRejected.class);
		assertEquals(NotFoundException.class.getName(), rejected.exceptionType());
		assertEquals("r", rejected.reason());

		assertInstanceOf(IllegalStateException.class, calls.get(2).failure().orElseThrow());
		assertTrue(recorder.violations().isEmpty(), recorder.violations().toString());
	}

	@Test
	void aListenerThatThrowsNeverFailsTheCall ( ) {
		Mock domain = buildBoundedContext(baseBuilder()
				.listener(event -> { throw new IllegalStateException("monitoring store down"); })
				.adapter(adapter).monitored().forPort(GatewayPort.class));
		GatewayPort gateway = domain.port(GatewayPort.class);

		assertEquals("answer to q", gateway.answer("q"));
		assertThrows(NotFoundException.class, () -> gateway.refuse("r"), "the adapter's exception, not the listener's");
	}

	@Test
	void theDurationIsTheCallsAndNotTheReportingOfIt ( ) {
		Mock domain = buildBoundedContext(baseBuilder()
				.listener(event -> {
					if ( event.data() instanceof PortCalled ) {
						sleep(300);
					}
					observed.add(event);
				})
				.adapter(adapter).monitored().forPort(GatewayPort.class));
		GatewayPort gateway = domain.port(GatewayPort.class);

		gateway.answer("one");
		gateway.answer("two");

		for ( PortCalled called : all(PortCalled.class) ) {
			assertTrue(called.durationMicros() < 250_000, "a slow listener is not part of the call: " + called.durationMicros());
		}
	}

	@Test
	void withoutAListenerTheCallsStillWorkAndAreObserved ( ) {
		RecordingBoundedContextObserver recorder = new RecordingBoundedContextObserver();
		Mock domain = buildBoundedContext(baseBuilder().observer(recorder).adapter(adapter).monitored().forPort(GatewayPort.class));

		assertEquals("answer to q", domain.port(GatewayPort.class).answer("q"));
		assertEquals(1, recorder.recordings(Observation.PortCall.class).size());
	}

	// ════════════════════════════════════════════════════════════════════
	// persisted
	// ════════════════════════════════════════════════════════════════════

	/**
	 * A monitoring listener persists these events and a dashboard reads them back in another process, so
	 * every shape — the caller, the slice, the failure, the window's instants and histogram, the inventory —
	 * has to survive the store.
	 */
	@Test
	void everyPortEventSurvivesTheRoundTripThroughAMonitoringStream ( ) {
		EventStream<BoundedContextEvent> monitoring = EventStore.on(eventStorage()).build()
				.getEventStream(EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		StreamAppendingBoundedContextListener persisting = new StreamAppendingBoundedContextListener(monitoring);
		BoundedContextBuilder<Mock> builder = baseBuilder()
				.listener(event -> { observed.add(event); persisting.on(event); })
				.adapter(adapter).monitored(PortMonitoring.perCall().summarizing(Duration.ofHours(1), "greet")).forPort(GatewayPort.class);
		builder.features().rootPackage(CallGatewayFeatureSlice.class.getPackage()).done();
		Mock domain = buildBoundedContext(builder);
		GatewayPort gateway = domain.port(GatewayPort.class);

		domain.execute(new CallGatewayCommand(gateway, g -> g.answer("q")));
		assertThrows(NotFoundException.class, () -> gateway.refuse("r"));
		assertThrows(IllegalStateException.class, () -> gateway.breakDown("f"));
		gateway.greet("summarized");
		domain.stop();

		List<BoundedContextEvent> stored = monitoring.query(EventQuery.matchAll()).stream().map(Event::data).toList();
		for ( Class<? extends BoundedContextEvent> type : List.of(PortCalled.class, PortCallRejected.class, PortCallFailed.class,
				PortCallsSummarized.class, BoundedContextEvent.BoundedContextStarting.class) ) {
			List<? extends BoundedContextEvent> written = all(type);
			assertEquals(1, written.size(), type.getSimpleName());
			assertEquals(written, stored.stream().filter(type::isInstance).toList(), type.getSimpleName() + " reads back as it was written");
		}
	}

	// ════════════════════════════════════════════════════════════════════
	// helpers
	// ════════════════════════════════════════════════════════════════════

	private GatewayPort monitoredGateway ( PortMonitoring monitoring ) {
		Mock domain = buildBoundedContext(baseBuilder().listener(observed::add).adapter(adapter).monitored(monitoring).forPort(GatewayPort.class));
		GatewayPort gateway = domain.port(GatewayPort.class);
		observed.clear(); // the lifecycle events
		return gateway;
	}

	private BoundedContextBuilder<Mock> slicedBuilder ( PortMonitoring monitoring ) {
		BoundedContextBuilder<Mock> builder = baseBuilder().listener(observed::add).adapter(adapter).monitored(monitoring).forPort(GatewayPort.class);
		builder.features().rootPackage(CallGatewayFeatureSlice.class.getPackage()).done();
		builder.readmodel(GatewayLookupReadModel.class).live();
		return builder;
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	private List<EphemeralEvent<BoundedContextEvent>> observedOf ( Class<? extends BoundedContextEvent> type ) {
		synchronized ( observed ) {
			return observed.stream().filter(e -> type.isInstance(e.data())).toList();
		}
	}

	private <T extends BoundedContextEvent> List<T> all ( Class<T> type ) {
		return observedOf(type).stream().map(e -> type.cast(e.data())).toList();
	}

	private <T extends BoundedContextEvent> T only ( Class<T> type ) {
		List<T> found = all(type);
		assertEquals(1, found.size(), "exactly one " + type.getSimpleName() + ", got " + found);
		return found.get(0);
	}

	private EphemeralEvent<BoundedContextEvent> onlyEvent ( Class<? extends BoundedContextEvent> type ) {
		List<EphemeralEvent<BoundedContextEvent>> found = observedOf(type);
		assertEquals(1, found.size(), "exactly one " + type.getSimpleName());
		return found.get(0);
	}

	private void assertNone ( Class<? extends BoundedContextEvent> type ) {
		assertEquals(List.of(), all(type), "no " + type.getSimpleName());
	}

	private void assertNoPortEvents ( ) {
		assertNone(PortCalled.class);
		assertNone(PortCallRejected.class);
		assertNone(PortCallFailed.class);
		assertNone(PortCallsSummarized.class);
	}

	private int indexOf ( Class<? extends BoundedContextEvent> type ) {
		synchronized ( observed ) {
			for ( int i = 0; i < observed.size(); i++ ) {
				if ( type.isInstance(observed.get(i).data()) ) {
					return i;
				}
			}
		}
		return -1;
	}

	private static PortCallsSummarized summaryOf ( List<PortCallsSummarized> summaries, String method ) {
		List<PortCallsSummarized> found = summaries.stream().filter(s -> s.method().equals(method)).toList();
		assertEquals(1, found.size(), "one summary for " + method + ", got " + summaries);
		return found.get(0);
	}

	private static String tag ( EphemeralEvent<?> event, String key ) {
		return event.tags().tag(key).map(t -> t.value()).orElse(null);
	}

	private static void sleep ( long ms ) {
		try {
			Thread.sleep(ms);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/** Reads the lookup read model, then calls the port itself. */
	static class ReadThenCallCommand implements Command<MockDomainEvent> {

		private final GatewayPort gateway;

		ReadThenCallCommand ( GatewayPort gateway ) {
			this.gateway = gateway;
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
			context.read(GatewayLookupReadModel.class, gateway);
			gateway.answer("after the read");
			context.noDecisionModels();
		}
	}

	static class LookupTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		private final GatewayPort gateway;

		LookupTranslator ( GatewayPort gateway ) {
			this.gateway = gateway;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( Event<MockInboundEvent> event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			if ( event.data() instanceof SomeInboundEvent some ) {
				context.event(new SecondDomainEvent(gateway.answer(some.someValue())));
			}
		}
	}

	record ItemToLookUp ( String value, String correlationId ) implements CorrelatedTodoItem { }

	static class ItemsToLookUp implements TodoListReadModel<MockDomainEvent,ItemToLookUp> {

		private final List<ItemToLookUp> items = Collections.synchronizedList(new ArrayList<>());
		private volatile EventReference lastEventReference;

		@Override
		public String readmodelName ( ) {
			return "items-to-look-up";
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
				case FirstDomainEvent f -> items.add(new ItemToLookUp(f.value(), Tracing.readFrom(event).correlationId()));
				case SecondDomainEvent s -> items.removeIf(item -> s.value().startsWith("answer to " + item.value()));
				default -> { }
			}
			lastEventReference = event.reference();
		}

		@Override
		public Stream<ItemToLookUp> streamItems ( Limit limit ) {
			return List.copyOf(items).stream().limit(limit.isSet() ? limit.value() : Long.MAX_VALUE);
		}

		@Override
		public Optional<EventReference> lastEventReference ( ) {
			return Optional.ofNullable(lastEventReference);
		}
	}

	static class LookUpAutomation implements Automation<ItemToLookUp,MockDomainEvent,MockOutboundEvent> {

		private final ItemsToLookUp todoList;
		private final GatewayPort gateway;

		LookUpAutomation ( ItemsToLookUp todoList, GatewayPort gateway ) {
			this.todoList = todoList;
			this.gateway = gateway;
		}

		@Override
		public TodoListReadModel<MockDomainEvent,ItemToLookUp> getTodoList ( ) {
			return todoList;
		}

		@Override
		public Optional<EventReference> handle ( ItemToLookUp item, AutomationContext<MockDomainEvent,MockOutboundEvent> context ) {
			return context.event(new SecondDomainEvent(gateway.answer(item.value())), "looked-up:" + item.value());
		}
	}

}
