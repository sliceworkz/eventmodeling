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
package org.sliceworkz.eventmodeling.module.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.PolicyRejectionHandling;
import org.sliceworkz.eventmodeling.automation.PolicyStart;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.misplacedpolicy.MisplacedPolicyFeatureSlice;
import org.sliceworkz.eventmodeling.mock.policy.SecondFromFirstFeatureSlice;
import org.sliceworkz.eventmodeling.mock.policy.SecondFromFirstPolicy;
import org.sliceworkz.eventmodeling.slices.SliceType;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A policy registered on a bounded context: the command it issues whenever its event happens, where it
 * starts, what a rejection does, an operator's skip, and what {@code build()} refuses about how it was
 * registered. The reaction itself — the trace, the keys, a redelivery — is pinned through the published
 * {@code PolicyTest} base in {@code PolicyTestRunsOnEveryBackendTest}.
 */
public class PolicyModuleTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "PolicyBoundedContext";
	private static final String POLICY = "SecondFromFirstPolicy";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private String previousInitial;
	private String previousMax;

	@BeforeEach
	void shortenRetryPacing ( ) {
		previousInitial = System.setProperty("sliceworkz.eventmodeling.projector.retry.initial.ms", "50");
		previousMax = System.setProperty("sliceworkz.eventmodeling.projector.retry.max.ms", "200");
	}

	@AfterEach
	void restoreRetryPacing ( ) {
		restore("sliceworkz.eventmodeling.projector.retry.initial.ms", previousInitial);
		restore("sliceworkz.eventmodeling.projector.retry.max.ms", previousMax);
	}

	private static void restore ( String property, String previous ) {
		if ( previous == null ) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private EventStream<MockDomainEvent> domain ( ) {
		return eventStore().getEventStream(BoundedContextStreams.domain(CONTEXT_NAME), MockDomainEvent.class);
	}

	private List<Event<MockDomainEvent>> seconds ( ) {
		return domain().query(EventQuery.forTypes(SecondDomainEvent.class));
	}

	private List<String> secondValues ( ) {
		return seconds().stream().map(e -> ((SecondDomainEvent) e.data()).value()).toList();
	}

	private <T extends BoundedContextEvent> List<T> receivedOf ( Class<T> type ) {
		synchronized ( received ) {
			return received.stream().filter(type::isInstance).map(type::cast).toList();
		}
	}

	// ── what a reaction does ─────────────────────────────────────────────────

	@ForEachBackend
	void wheneverItsEventHappensThePolicyIssuesItsCommandContinuingTheFlowAndNamingTheCause ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromTheBeginning().stallOnRejection();
		Mock context = buildBoundedContext(builder);

		Tracing tracing = Tracing.init(InstanceFactory.determine("unittests")).actor("alice").correlationId("flow-1");
		EventReference first = context.event(new FirstDomainEvent("a"), tracing).orElseThrow();

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		Event<MockDomainEvent> raised = seconds().get(0);
		assertEquals(new SecondDomainEvent("a"), raised.data());
		Tracing raisedTracing = Tracing.readFrom(raised);
		assertEquals(first.id().value(), raisedTracing.causationId(), "the command's events name the event the policy reacted to");
		assertEquals("flow-1", raisedTracing.correlationId(), "and continue its flow");
		assertEquals(POLICY, raisedTracing.actor(), "with the policy as the actor");
		assertEquals(Reaction.CHANNEL, raisedTracing.channel());
	}

	@Test
	void anEventThePolicyDoesNotActOnIsLetPass ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromTheBeginning().stallOnRejection();
		Mock context = buildBoundedContext(builder);

		context.event(new FirstDomainEvent("ignore-me"));
		context.event(new FirstDomainEvent("b"));

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("b"), secondValues());
	}

	@Test
	void aPolicyIsAProcessorAnnouncedWithItsChoicesThatAnOperatorCanStopAndRestart ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromNowOn().skipRejections();
		Mock context = buildBoundedContext(builder);

		BoundedContextEvent.PolicyStarted started = receivedOf(BoundedContextEvent.PolicyStarted.class).get(0);
		assertEquals(POLICY, started.policy());
		assertEquals(PolicyStart.FROM_NOW_ON, started.start());
		assertEquals(PolicyRejectionHandling.SKIP, started.onRejection());

		ProcessorStatus status = context.processors().stream().filter(p -> p.kind() == ProcessorKind.POLICY).findFirst().orElseThrow();
		assertEquals(POLICY, status.name());

		assertTrue(context.stopProcessor(ProcessorKind.POLICY, POLICY));
		context.event(new FirstDomainEvent("while-stopped"));
		assertTrue(context.restartProcessor(ProcessorKind.POLICY, POLICY));

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("while-stopped"), secondValues(), "nothing is lost while a policy is stopped");
	}

	// ── where it starts ──────────────────────────────────────────────────────

	@Test
	void fromTheBeginningItReactsToTheHistoryRecordedBeforeItWasDeployed ( ) {
		domain().append(Event.of(new FirstDomainEvent("old"), org.sliceworkz.eventstore.events.Tags.none()));

		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromTheBeginning().stallOnRejection();
		buildBoundedContext(builder);

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("old"), secondValues());
	}

	@Test
	void fromNowOnItReactsOnlyToWhatHappensAfterItFirstLeads ( ) {
		domain().append(Event.of(new FirstDomainEvent("old"), org.sliceworkz.eventstore.events.Tags.none()));

		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromNowOn().stallOnRejection();
		Mock context = buildBoundedContext(builder);
		context.event(new FirstDomainEvent("new"));

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("new"), secondValues(), "the history recorded before the policy was deployed is not reacted to");
	}

	/**
	 * Starting from now on bookmarks the policy at the head of the stream as both positions: the resume point
	 * and how far it has read. With the resume point alone, the bookmark would show no read position until
	 * something was appended — read past, yet seemingly never read.
	 */
	@Test
	void fromNowOnTheBookmarkAtTheHeadRecordsTheHeadAsReadToo ( ) {
		EventReference old = domain().append(Event.of(new FirstDomainEvent("old"), org.sliceworkz.eventstore.events.Tags.none())).get(0).reference();

		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromNowOn().stallOnRejection();
		buildBoundedContext(builder);

		String reader = CONTEXT_NAME + "/policy/" + POLICY + "[shared]";
		waitBecauseOfEventualConsistency(() -> domain().findBookmark(reader).isPresent());
		var bookmark = domain().findBookmark(reader).orElseThrow();
		assertEquals(old.id(), bookmark.reference().orElseThrow().id());
		assertEquals(old.id(), bookmark.readUpTo().orElseThrow().id(), "the head is recorded as read, not only as handled");
	}

	// ── what a rejection does ────────────────────────────────────────────────

	@Test
	void aPolicyThatStallsOnRejectionHoldsEverythingBehindTheRejectedEventUntilAnOperatorSkipsIt ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromTheBeginning().stallOnRejection();
		Mock context = buildBoundedContext(builder);

		EventReference rejected = context.event(new FirstDomainEvent("reject-1")).orElseThrow();
		context.event(new FirstDomainEvent("b"));

		waitBecauseOfEventualConsistency(() -> receivedOf(BoundedContextEvent.PolicyFailed.class).size() >= 2);
		BoundedContextEvent.PolicyFailed failed = receivedOf(BoundedContextEvent.PolicyFailed.class).get(0);
		assertEquals(rejected.id(), failed.failedAt().id(), "the failure names the event the policy is stalled on");
		assertTrue(seconds().isEmpty(), "nothing behind the stalled event is reacted to");

		assertFalse(context.skipStalledEvent(ProcessorKind.POLICY, POLICY, EventId.create()), "only the stalled event can be skipped");
		assertTrue(context.skipStalledEvent(ProcessorKind.POLICY, POLICY, rejected.id()));

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("b"), secondValues());
		BoundedContextEvent.PolicyEventSkipped skipped = receivedOf(BoundedContextEvent.PolicyEventSkipped.class).get(0);
		assertEquals(rejected.id(), skipped.event().id());
		assertEquals(BoundedContextEvent.PolicySkipReason.OPERATOR, skipped.reason());
	}

	@Test
	void aPolicyThatSkipsRejectionsRecordsTheRejectionAndMovesOn ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromTheBeginning().skipRejections();
		Mock context = buildBoundedContext(builder);

		EventReference rejected = context.event(new FirstDomainEvent("reject-1")).orElseThrow();
		context.event(new FirstDomainEvent("b"));

		waitBecauseOfEventualConsistency(() -> seconds().size() >= 1);
		assertEquals(List.of("b"), secondValues());
		BoundedContextEvent.PolicyEventSkipped skipped = receivedOf(BoundedContextEvent.PolicyEventSkipped.class).get(0);
		assertEquals(rejected.id(), skipped.event().id());
		assertEquals(BoundedContextEvent.PolicySkipReason.REJECTED, skipped.reason());
		assertEquals("rejected reject-1", skipped.rejection());
		assertFalse(receivedOf(BoundedContextEvent.CommandRejected.class).isEmpty(), "the command's own rejection is reported as ever");
		assertTrue(receivedOf(BoundedContextEvent.PolicyFailed.class).isEmpty(), "a rejection skipped by registration is no failure");
	}

	@Test
	void onlyAPolicyCanSkipAStalledEvent ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromNowOn().stallOnRejection();
		Mock context = buildBoundedContext(builder);

		assertThrows(IllegalArgumentException.class, () -> context.skipStalledEvent(ProcessorKind.PUBLISHER, POLICY, EventId.create()));
		assertThrows(IllegalArgumentException.class, () -> context.skipStalledEvent(ProcessorKind.POLICY, "NoSuchPolicy", EventId.create()));
	}

	// ── how a policy has to be registered ────────────────────────────────────

	@Test
	void aPolicyRegisteredWithoutItsTwoChoicesIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy());

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("policy registered without saying where it starts and what a rejection does: SecondFromFirstPolicy"
				+ " (say .fromNowOn() or .fromTheBeginning() and .stallOnRejection() or .skipRejections())", e.getMessage());
	}

	@Test
	void aPolicyRegisteredWithoutItsRejectionHandlingIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.policy(new SecondFromFirstPolicy()).fromNowOn();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("policy registered without saying where it starts and what a rejection does: SecondFromFirstPolicy"
				+ " (say .stallOnRejection() or .skipRejections())", e.getMessage());
	}

	@Test
	void aPolicyRegisteredFromAnotherAspectThanAutomationIsRejected ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(MisplacedPolicyFeatureSlice.class.getPackage()).done();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals("policy registered outside the automation aspect: SecondFromFirstPolicy (registered by slice MisplacedPolicy"
				+ " from configureProjection -- register it from configureAutomation)", e.getMessage());
	}

	@Test
	void aSliceHoldingAPolicyIsAnAutomation ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.features().rootPackage(SecondFromFirstFeatureSlice.class.getPackage()).done();
		buildBoundedContext(builder);

		BoundedContextEvent.BoundedContextStarting starting = receivedOf(BoundedContextEvent.BoundedContextStarting.class).get(0);
		BoundedContextEvent.FeatureSlice slice = starting.enabledFeatures().stream()
				.filter(s -> s.name().equals("SecondFromFirst")).findFirst().orElseThrow();
		assertEquals(SliceType.AUTOMATION, slice.type());
		assertTrue(slice.members().stream().anyMatch(m -> m.kind() == BoundedContextEvent.MemberKind.POLICY && m.name().equals(POLICY)));
	}

}
