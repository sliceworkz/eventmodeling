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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandExecuted;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandRejected;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.Overriding;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventmodeling.rules.RuleViolationException;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Pins how the kernel judges the business rules a command checks: every enforcement level's effect on the
 * execution, the command's own decision on who may override, what is recorded (in what the command reads
 * back through {@code ruleViolations()}, and in the rule tags on the stored events), the evaluation that
 * previews an execution without appending, and the observability record of both.
 * <p>
 * Most of it is framework behaviour and runs once against the in-memory store. The override quota runs on
 * every backend, because it depends on a tag query — the actor's tag together with the rule's — finding
 * exactly the events it should, and on a DCB conflict between two overrides racing past it.
 */
public class BusinessRuleEnforcementTest extends AbstractMockDomainTest {

	private static final BusinessRule STRICT = BusinessRule.of("strict", "Strictly enforced rule.");
	private static final BusinessRule DEFERRED = BusinessRule.of("deferred", "Deferred rule.").enforcedAt(EnforcementLevel.DEFERRED_ENFORCEMENT);
	private static final BusinessRule PRE_AUTHORIZED = BusinessRule.of("pre-authorized", "Pre-authorized override rule.").enforcedAt(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE);
	private static final BusinessRule POST_JUSTIFIED = BusinessRule.of("post-justified", "Post-justified override rule.").enforcedAt(EnforcementLevel.POST_JUSTIFIED_OVERRIDE);
	private static final BusinessRule EXPLAINED = BusinessRule.of("explained", "Override with explanation rule.").enforcedAt(EnforcementLevel.OVERRIDE_WITH_EXPLANATION);
	private static final BusinessRule GUIDELINE = BusinessRule.of("guideline", "Guideline.").enforcedAt(EnforcementLevel.GUIDELINE);

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
	private final RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();

	private Mock domain ( ) {
		return buildBoundedContext(BoundedContext.newBuilder(Mock.class)
				.name("rules")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.observer(observer)
				.listener(event -> received.add(event.data())));
	}

	// ── the command under test ──────────────────────────────────────────────

	static class AllFirstEvents implements DecisionModel<MockDomainEvent> {
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) { }
	}

	/**
	 * Reads one decision model, lets the test check whatever rules it wants on the context, reads back what
	 * would be recorded, and raises one event.
	 */
	static class RuledCommand implements Command<MockDomainEvent>, Overriding {

		private final Overrides overrides;
		private final BiConsumer<CommandContext<MockDomainEvent, MockDomainEvent>, RuledCommand> rules;
		List<RuleViolation> recorded;
		Optional<String> actor;

		RuledCommand ( Overrides overrides, BiConsumer<CommandContext<MockDomainEvent, MockDomainEvent>, RuledCommand> rules ) {
			this.overrides = overrides;
			this.rules = rules;
		}

		@Override
		public Overrides overrides ( ) {
			return overrides;
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
			var result = context.decisionModels(new AllFirstEvents());
			actor = context.actor();
			rules.accept(context, this);
			recorded = context.ruleViolations();
			result.raiseEvent(new FirstDomainEvent("raised"), Tags.of("entity", "1"));
		}
	}

	private static RuledCommand violating ( Overrides overrides, BusinessRule... rules ) {
		return new RuledCommand(overrides, (context, command) -> {
			for ( BusinessRule rule: rules ) {
				context.check(rule, true, rule.id() + " violated");
			}
		});
	}

	private List<Event<MockDomainEvent>> stored ( ) {
		return domainStream().query(EventQuery.matchAll());
	}

	private EventStream<MockDomainEvent> domainStream ( ) {
		return eventStore().getEventStream(BoundedContextStreams.domain("rules"), MockDomainEvent.class);
	}

	// ── enforcement levels ──────────────────────────────────────────────────

	@Test
	void aStrictlyEnforcedRuleBlocksWhateverIsRequested ( ) {
		Mock domain = domain();

		RuleViolationException rejected = assertThrows(RuleViolationException.class,
				() -> domain.execute(violating(Overrides.of("strict"), STRICT)));

		assertEquals(Evaluation.Outcome.BLOCKED, rejected.evaluation().outcome());
		assertEquals(Verdict.BLOCKS, rejected.evaluation().judgementOf(STRICT).orElseThrow().verdict());
		assertTrue(stored().isEmpty(), "a blocked execution appends nothing");
	}

	@Test
	void aRuleViolationIsReportedAsARejectionNotAFailure ( ) {
		Mock domain = domain();

		RuleViolationException rejected = assertThrows(RuleViolationException.class, () -> domain.execute(violating(Overrides.none(), STRICT)));

		assertTrue(rejected instanceof BusinessException, "a rule violation is a BusinessException");
		CommandRejected rejection = received.stream().filter(CommandRejected.class::isInstance).map(CommandRejected.class::cast).findFirst().orElseThrow();
		assertEquals(rejected.getMessage(), rejection.reason());
		assertTrue(rejection.reason().contains("strict violated"), rejection.reason());
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandFailed || e instanceof CommandExecuted), received.toString());
	}

	@Test
	void aPreAuthorizedOverrideAuthorizesNobodyUnlessTheCommandDecides ( ) {
		Mock domain = domain();

		RuleViolationException rejected = assertThrows(RuleViolationException.class,
				() -> domain.execute(violating(Overrides.of("pre-authorized"), PRE_AUTHORIZED)));

		var judgement = rejected.evaluation().judgementOf(PRE_AUTHORIZED).orElseThrow();
		assertEquals(Verdict.BLOCKS, judgement.verdict());
		assertEquals("no pre-authorization to override this rule", judgement.reason());
	}

	@Test
	void aPreAuthorizedOverrideNeedsTheRequestAndTheAuthorization ( ) {
		Mock domain = domain();
		BiConsumer<CommandContext<MockDomainEvent, MockDomainEvent>, RuledCommand> authorized =
				(context, command) -> context.check(PRE_AUTHORIZED, true, "over the limit").overridableWhen(true, null);

		RuleViolationException unrequested = assertThrows(RuleViolationException.class,
				() -> domain.execute(new RuledCommand(Overrides.none(), authorized)));
		assertEquals(Evaluation.Outcome.NEEDS_OVERRIDE, unrequested.evaluation().outcome());
		assertTrue(unrequested.evaluation().judgementOf(PRE_AUTHORIZED).orElseThrow().overridable());

		RuledCommand requested = new RuledCommand(Overrides.of("pre-authorized"), authorized);
		domain.execute(requested);

		assertEquals(List.of(new RuleViolation("pre-authorized", EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, Disposition.OVERRIDDEN, "over the limit", null)), requested.recorded);
		assertTrue(stored().get(0).tags().tags().contains(RuleTags.overridden(PRE_AUTHORIZED)), stored().get(0).tags().toString());
	}

	@Test
	void theCommandCanRefuseAnOverrideAndSayWhy ( ) {
		Mock domain = domain();

		RuleViolationException rejected = assertThrows(RuleViolationException.class, () -> domain.execute(new RuledCommand(Overrides.of("post-justified"),
				(context, command) -> context.check(POST_JUSTIFIED, true, "late").overridableWhen(false, "only managers may"))));

		var judgement = rejected.evaluation().judgementOf(POST_JUSTIFIED).orElseThrow();
		assertEquals(Verdict.BLOCKS, judgement.verdict());
		assertEquals("only managers may", judgement.reason());
		assertFalse(judgement.overridable());
	}

	@Test
	void aPostJustifiedOverrideIsRecordedAsPendingJustification ( ) {
		Mock domain = domain();

		RuledCommand command = violating(Overrides.none().with(POST_JUSTIFIED, "customer is waiting"), POST_JUSTIFIED);
		domain.execute(command);

		assertEquals(List.of(new RuleViolation("post-justified", EnforcementLevel.POST_JUSTIFIED_OVERRIDE, Disposition.JUSTIFICATION_PENDING, "post-justified violated", "customer is waiting")), command.recorded);
		Tags tags = stored().get(0).tags();
		assertTrue(tags.tags().contains(RuleTags.overridden(POST_JUSTIFIED)), tags.toString());
		assertTrue(tags.tags().contains(RuleTags.justificationPending(POST_JUSTIFIED)), tags.toString());
	}

	@Test
	void anOverrideWithExplanationIsOnlyAcceptedWithOne ( ) {
		Mock domain = domain();

		RuleViolationException unexplained = assertThrows(RuleViolationException.class,
				() -> domain.execute(violating(Overrides.of("explained"), EXPLAINED)));
		var judgement = unexplained.evaluation().judgementOf(EXPLAINED).orElseThrow();
		assertEquals(Verdict.OVERRIDE_REQUIRED, judgement.verdict());
		assertTrue(judgement.explanationRequired());

		RuledCommand explained = violating(Overrides.none().with("explained", "  a good reason  "), EXPLAINED);
		domain.execute(explained);
		assertEquals("a good reason", explained.recorded.get(0).explanation());
		assertEquals(Disposition.OVERRIDDEN, explained.recorded.get(0).disposition());
	}

	@Test
	void deferredEnforcementAndGuidelinesGoAheadUnaskedAndAreRecorded ( ) {
		Mock domain = domain();

		RuledCommand command = violating(Overrides.none(), DEFERRED, GUIDELINE);
		domain.execute(command);

		assertEquals(List.of(Disposition.ENFORCEMENT_DEFERRED, Disposition.GUIDELINE_NOT_FOLLOWED), command.recorded.stream().map(RuleViolation::disposition).toList());
		Tags tags = stored().get(0).tags();
		assertTrue(tags.tags().contains(RuleTags.deferred(DEFERRED)), tags.toString());
		assertTrue(tags.tags().contains(RuleTags.notFollowed(GUIDELINE)), tags.toString());
		assertFalse(tags.tags().stream().anyMatch(t -> RuleTags.OVERRIDDEN.equals(t.key())), "neither is an override: " + tags);
	}

	@Test
	void everyViolatedRuleIsReportedAtOnceInTheOrderItWasChecked ( ) {
		Mock domain = domain();

		RuleViolationException rejected = assertThrows(RuleViolationException.class,
				() -> domain.execute(violating(Overrides.none(), GUIDELINE, EXPLAINED, STRICT, DEFERRED)));

		assertEquals(List.of("guideline", "explained", "strict", "deferred"), rejected.evaluation().judgements().stream().map(j -> j.rule()).toList());
		assertEquals(List.of("explained", "strict"), rejected.evaluation().stopping().stream().map(j -> j.rule()).toList());
	}

	@Test
	void aRuleThatIsNotViolatedIsNotJudgedAndItsOverrideRecordsNothing ( ) {
		Mock domain = domain();

		RuledCommand command = new RuledCommand(Overrides.none().with("explained", "just in case"),
				(context, c) -> context.check(EXPLAINED, false, "never shown"));
		domain.execute(command);

		assertEquals(List.of(), command.recorded);
		assertTrue(stored().get(0).tags().tags().stream().noneMatch(t -> t.key().startsWith("x-rule")), stored().get(0).tags().toString());
	}

	@Test
	void checkingARuleAfterReadingTheViolationsIsABug ( ) {
		Mock domain = domain();

		IllegalStateException bug = assertThrows(IllegalStateException.class, () -> domain.execute(new Command<MockDomainEvent>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				context.noDecisionModels();
				context.ruleViolations();
				context.check(GUIDELINE, true, "too late");
			}
		}));

		assertTrue(bug.getMessage().contains("after ruleViolations() was read"), bug.getMessage());
		assertTrue(received.stream().anyMatch(CommandFailed.class::isInstance), "a bug is reported as a failure: " + received);
	}

	@Test
	void theActorIsTheOneTheTracingNames ( ) {
		Mock domain = domain();

		RuledCommand anonymous = violating(Overrides.none());
		domain.execute(anonymous);
		assertEquals(Optional.empty(), anonymous.actor);

		RuledCommand alice = violating(Overrides.none());
		domain.execute(alice, Tracing.actorAndChannel("alice", "test"));
		assertEquals(Optional.of("alice"), alice.actor);
	}

	// ── evaluation ──────────────────────────────────────────────────────────

	@Test
	void anEvaluationJudgesLikeAnExecutionAndAppendsNothing ( ) {
		Mock domain = domain();

		Evaluation evaluation = domain.evaluate(violating(Overrides.none().with("explained", "why not"), EXPLAINED, PRE_AUTHORIZED, GUIDELINE));

		assertEquals(Evaluation.Outcome.BLOCKED, evaluation.outcome());
		assertEquals(Verdict.OVERRIDDEN, evaluation.judgementOf(EXPLAINED).orElseThrow().verdict());
		assertEquals(Verdict.BLOCKS, evaluation.judgementOf(PRE_AUTHORIZED).orElseThrow().verdict());
		assertEquals(Verdict.ADVISED, evaluation.judgementOf(GUIDELINE).orElseThrow().verdict());
		assertTrue(stored().isEmpty(), "an evaluation appends nothing");
		assertTrue(received.stream().noneMatch(e -> e instanceof CommandExecuted || e instanceof CommandRejected),
				"an evaluation is not an execution in the monitoring record: " + received);
	}

	@Test
	void anEvaluationTellsWhichOverridesAreNeeded ( ) {
		Mock domain = domain();

		Evaluation needed = domain.evaluate(violating(Overrides.none(), EXPLAINED, DEFERRED));
		assertEquals(Evaluation.Outcome.NEEDS_OVERRIDE, needed.outcome());
		assertEquals(List.of("explained"), needed.overridesRequired().stream().map(j -> j.rule()).toList());

		Evaluation clear = domain.evaluate(violating(Overrides.none().with("explained", "because"), EXPLAINED, DEFERRED));
		assertTrue(clear.wouldSucceed());
		assertEquals(List.of(Disposition.OVERRIDDEN, Disposition.ENFORCEMENT_DEFERRED), clear.recorded().stream().map(RuleViolation::disposition).toList());
	}

	@Test
	void aBusinessExceptionIsTheAnswerRejectedWithTheRulesCheckedBeforeIt ( ) {
		Mock domain = domain();

		Evaluation evaluation = domain.evaluate(new RuledCommand(Overrides.none(), (context, command) -> {
			context.check(GUIDELINE, true, "advice");
			BusinessException.because("account does not exist");
		}));

		assertEquals(Evaluation.Outcome.REJECTED, evaluation.outcome());
		assertEquals("account does not exist", evaluation.rejection());
		assertEquals(List.of("guideline"), evaluation.judgements().stream().map(j -> j.rule()).toList());
	}

	@Test
	void anEvaluationSpendsNoIdempotencyKey ( ) {
		Mock domain = domain();
		RuledCommand command = violating(Overrides.none(), GUIDELINE);

		domain.evaluate(command);
		assertTrue(domain.execute(command, "key-1").isPresent(), "the key is still unspent after an evaluation");
	}

	@Test
	void anEvaluationIsObservedAsAnEvaluation ( ) {
		Mock domain = domain();

		domain.evaluate(violating(Overrides.none(), EXPLAINED), Tracing.actorAndChannel("bob", "test"));

		var recording = observer.last(Observation.CommandEvaluation.class);
		assertEquals("bob", recording.observation(Observation.CommandEvaluation.class).tracing().actor());
		assertEquals(new Outcome.Evaluated(Evaluation.Outcome.NEEDS_OVERRIDE, 1), recording.outcome(Outcome.Evaluated.class));
		assertTrue(observer.observations(Observation.CommandExecution.class).isEmpty(), "no execution was observed");
		assertEquals(List.of(), observer.violations());
	}

	// ── the command's own decision, inside its consistency boundary ─────────

	/**
	 * The decision model a quota of overrides per actor is taken on: the events carrying the actor's tag and
	 * the rule's override tag, both written by the kernel.
	 */
	static class OverridesBy implements DecisionModel<MockDomainEvent> {
		private final String actor;
		int count;

		OverridesBy ( String actor ) {
			this.actor = actor;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), RuleTags.overriddenBy(actor, PRE_AUTHORIZED));
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			count++;
		}
	}

	static class QuotaCommand implements Command<MockDomainEvent>, Overriding {
		private final Runnable beforeRaising;

		QuotaCommand ( Runnable beforeRaising ) {
			this.beforeRaising = beforeRaising;
		}

		@Override
		public Overrides overrides ( ) {
			return Overrides.of("pre-authorized");
		}

		@Override
		public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
			var overrides = new OverridesBy(context.actor().orElse("nobody"));
			var result = context.decisionModels(overrides);
			context.check(PRE_AUTHORIZED, true, "exception").overridableWhen(overrides.count < 2, "quota of 2 reached");
			beforeRaising.run();
			result.raiseEvent(new FirstDomainEvent("override"), Tags.none());
		}
	}

	@ForEachBackend
	void anOverrideQuotaPerActorIsTakenOnTheRuleTags ( ) {
		Mock domain = domain();
		Tracing alice = Tracing.actorAndChannel("alice", "test");
		Tracing bob = Tracing.actorAndChannel("bob", "test");

		domain.execute(new QuotaCommand(() -> { }), alice);
		domain.execute(new QuotaCommand(() -> { }), alice);
		RuleViolationException third = assertThrows(RuleViolationException.class, () -> domain.execute(new QuotaCommand(() -> { }), alice));
		assertEquals("quota of 2 reached", third.evaluation().judgementOf(PRE_AUTHORIZED).orElseThrow().reason());

		domain.execute(new QuotaCommand(() -> { }), bob);	// another actor's quota is untouched

		List<Event<MockDomainEvent>> stored = stored();
		assertEquals(3, stored.size());
		assertEquals(List.of("alice", "alice", "bob"), stored.stream().map(e -> Tracing.readFrom(e).actor()).toList());
		assertTrue(stored.stream().allMatch(e -> e.tags().tags().contains(Tag.of(RuleTags.OVERRIDDEN, "pre-authorized"))));
	}

	@ForEachBackend
	void twoOverridesRacingPastAQuotaCannotBothSucceed ( ) {
		Mock domain = domain();
		Tracing alice = Tracing.actorAndChannel("alice", "test");
		domain.execute(new QuotaCommand(() -> { }), alice);

		// alice's second override lands after this command decided on a count of one, and before it appends
		QuotaCommand racing = new QuotaCommand(() -> domain.execute(new QuotaCommand(() -> { }), alice));

		assertThrows(OptimisticLockingException.class, () -> domain.execute(racing, alice));
		RuleViolationException retried = assertThrows(RuleViolationException.class,
				() -> domain.executeWithRetry(new QuotaCommand(() -> { }), alice, org.sliceworkz.eventmodeling.commands.RetryPolicy.DEFAULT));
		assertEquals("quota of 2 reached", retried.evaluation().judgementOf(PRE_AUTHORIZED).orElseThrow().reason());
		assertEquals(2, stored().size());
	}

}
