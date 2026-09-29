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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarting;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.CommandRejected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.module.dcb.BusinessRuleEnforcementTest.RuledCommand;
import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.RuleFollowUp;
import org.sliceworkz.eventmodeling.rules.RuleJudgement;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins what the kernel reports so the use of business rules can be audited from outside the code: the link
 * from a justification or an enforcement back to the violation it settles, the judgements on a rejection,
 * and the rulebook announced when a context starts — each of them also as it survives a monitoring stream.
 * <p>
 * Framework behaviour rather than storage behaviour, so plain {@code @Test}s against the in-memory store: the
 * tags are ordinary tags, and every backend's round trip of a tag is pinned by the eventstore's own TCK.
 */
public class BusinessRuleAuditTrailTest extends AbstractMockDomainTest {

	private static final BusinessRule STRICT = BusinessRule.of("strict", "Strictly enforced rule.");
	private static final BusinessRule POST_JUSTIFIED = BusinessRule.of("post-justified", "Post-justified override rule.").enforcedAt(EnforcementLevel.POST_JUSTIFIED_OVERRIDE);
	private static final BusinessRule DEFERRED = BusinessRule.of("deferred", "Deferred rule.").enforcedAt(EnforcementLevel.DEFERRED_ENFORCEMENT);

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private Mock domain ( BusinessRule... rulebook ) {
		return domain(null, rulebook);
	}

	private Mock domain ( EventStream<BoundedContextEvent> kernelStream, BusinessRule... rulebook ) {
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name("rules")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.businessRules(rulebook)
				.listener(event -> received.add(event.data()));
		if ( kernelStream != null ) {
			builder.listener(new StreamAppendingBoundedContextListener(kernelStream));
		}
		return buildBoundedContext(builder);
	}

	private EventStream<MockDomainEvent> domainStream ( ) {
		return eventStore().getEventStream(BoundedContextStreams.domain("rules"), MockDomainEvent.class);
	}

	private Event<MockDomainEvent> last ( ) {
		List<Event<MockDomainEvent>> events = domainStream().query(EventQuery.matchAll());
		return events.get(events.size() - 1);
	}

	/** A command that follows a violation up and raises one event, or none. */
	record FollowingUp ( boolean justify, BusinessRule rule, EventId event, String note, boolean raise ) implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
			var result = context.noDecisionModels();
			RuleFollowUp followUp = justify ? context.justifies(rule, event, note) : context.enforces(rule, event, note);
			if ( raise ) {
				result.raiseEvent(new FirstDomainEvent(followUp.kind() + " " + followUp.rule()), Tags.of("entity", "1"));
			}
		}
	}

	/** A command rejecting itself, with no business rule involved. */
	record Rejecting ( ) implements Command<MockDomainEvent> {
		@Override
		public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
			context.noDecisionModels();
			throw new BusinessException("makes no sense");
		}
	}

	// ── the follow-up link ──────────────────────────────────────────────────

	@Test
	void aJustificationIsTaggedWithTheOverrideItSettles ( ) {
		Mock domain = domain();
		domain.execute(new RuledCommand(Overrides.of("post-justified"), (context, command) -> context.check(POST_JUSTIFIED, true, "late")));
		Event<MockDomainEvent> override = last();
		assertTrue(override.tags().tags().contains(RuleTags.justificationPending(POST_JUSTIFIED)), override.tags().toString());

		domain.execute(new FollowingUp(true, POST_JUSTIFIED, override.reference().id(), "  customer waiting  ", true));

		Event<MockDomainEvent> justification = last();
		assertTrue(justification.tags().tags().contains(RuleTags.justified(POST_JUSTIFIED, override.reference().id())), justification.tags().toString());
		assertEquals("x-rule-justified:post-justified@" + override.reference().id().value(),
				RuleTags.justified(POST_JUSTIFIED, override.reference().id()).toString());
		assertTrue(justification.tags().toStrings().stream().noneMatch(t -> t.startsWith(RuleTags.ENFORCED)), justification.tags().toString());
	}

	@Test
	void anEnforcementIsTaggedWithTheDeferredViolationItSettles ( ) {
		Mock domain = domain();
		domain.execute(new RuledCommand(Overrides.none(), (context, command) -> context.check(DEFERRED, true, "too much")));
		EventId deferred = last().reference().id();

		domain.execute(new FollowingUp(false, DEFERRED, deferred, null, true));

		assertTrue(last().tags().tags().contains(RuleTags.enforced(DEFERRED, deferred)), last().tags().toString());
	}

	@Test
	void theFollowUpHandedToTheCommandIsWhatItRecords ( ) {
		Mock domain = domain();
		EventId event = EventId.create();
		List<RuleFollowUp> seen = new ArrayList<>();

		domain.execute(new Command<MockDomainEvent>() {
			@Override
			public void execute ( CommandContext<MockDomainEvent, MockDomainEvent> context ) {
				var result = context.noDecisionModels();
				seen.add(context.justifies(POST_JUSTIFIED, event, "  why  "));
				seen.add(context.enforces(DEFERRED, event, " "));
				result.raiseEvent(new FirstDomainEvent("both"), Tags.none());
			}
		});

		assertEquals(List.of(
				new RuleFollowUp("post-justified", event.value(), RuleFollowUp.Kind.JUSTIFIED, "why"),
				new RuleFollowUp("deferred", event.value(), RuleFollowUp.Kind.ENFORCED, null)), seen);
		assertTrue(last().tags().tags().containsAll(List.of(RuleTags.justified(POST_JUSTIFIED, event), RuleTags.enforced(DEFERRED, event))));
	}

	@Test
	void aFollowUpThatRaisesNothingIsRefused ( ) {
		Mock domain = domain();

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> domain.execute(new FollowingUp(true, POST_JUSTIFIED, EventId.create(), "why", false)));

		assertTrue(refused.getMessage().contains("raised no events"), refused.getMessage());
		assertTrue(received.stream().anyMatch(CommandFailed.class::isInstance), "a follow-up recorded nowhere is a bug: " + received);
		assertTrue(domainStream().query(EventQuery.matchAll()).isEmpty());
	}

	@Test
	void aFollowUpNeedsTheRuleAndTheEvent ( ) {
		Mock domain = domain();

		assertThrows(IllegalArgumentException.class, () -> domain.execute(new FollowingUp(true, null, EventId.create(), "why", true)));
		assertThrows(IllegalArgumentException.class, () -> domain.execute(new FollowingUp(false, DEFERRED, null, "why", true)));
	}

	// ── rejections ──────────────────────────────────────────────────────────

	@Test
	void aRejectionOnTheRulesCarriesTheirJudgements ( ) {
		Mock domain = domain();

		assertThrows(BusinessException.class, () -> domain.execute(new RuledCommand(Overrides.none(), (context, command) -> {
			context.check(STRICT, true, "over the maximum");
			context.check(POST_JUSTIFIED, true, "late").overridableWhen(false, "only managers may");
		})));

		CommandRejected rejected = only(CommandRejected.class);
		assertEquals(List.of(
				new RuleJudgement("strict", "Strictly enforced rule.", EnforcementLevel.STRICTLY_ENFORCED, "over the maximum", Verdict.BLOCKS, null, null),
				new RuleJudgement("post-justified", "Post-justified override rule.", EnforcementLevel.POST_JUSTIFIED_OVERRIDE, "late", Verdict.BLOCKS, "only managers may", null)),
				rejected.ruleJudgements());
	}

	@Test
	void aRejectionTheCommandThrewItselfCarriesNoJudgements ( ) {
		Mock domain = domain();

		assertThrows(BusinessException.class, () -> domain.execute(new Rejecting()));

		assertEquals(List.of(), only(CommandRejected.class).ruleJudgements());
	}

	// ── the rulebook ────────────────────────────────────────────────────────

	@Test
	void theDeclaredRulebookIsAnnouncedWhenTheContextStarts ( ) {
		domain(STRICT, POST_JUSTIFIED, STRICT);

		BoundedContextStarting starting = only(BoundedContextStarting.class);
		assertEquals(List.of(STRICT, POST_JUSTIFIED), starting.businessRules(), "declared once each, in declaration order");
		assertEquals(EnforcementLevel.POST_JUSTIFIED_OVERRIDE, starting.businessRules().get(1).enforcementLevel());
		assertEquals("Post-justified override rule.", starting.businessRules().get(1).statement());
	}

	@Test
	void aContextDeclaringNoRulesAnnouncesAnEmptyRulebook ( ) {
		domain();

		assertEquals(List.of(), only(BoundedContextStarting.class).businessRules());
	}

	@Test
	void twoDifferentDeclarationsOfOneRuleAreRefused ( ) {
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class).businessRules(STRICT);

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> builder.businessRules(STRICT.enforcedAt(EnforcementLevel.GUIDELINE)));
		assertTrue(refused.getMessage().contains("'strict'"), refused.getMessage());
		assertThrows(IllegalArgumentException.class, () -> builder.businessRules(BusinessRule.of("strict", "Another statement.")));
	}

	// ── on a monitoring stream ──────────────────────────────────────────────

	@Test
	void theRulebookAndTheJudgementsSurviveAMonitoringStream ( ) {
		EventStream<BoundedContextEvent> kernelStream = kernelStream();
		Mock domain = domain(kernelStream, STRICT, DEFERRED);
		assertThrows(BusinessException.class, () -> domain.execute(new RuledCommand(Overrides.none(), (context, command) -> context.check(STRICT, true, "over"))));

		List<BoundedContextEvent> persisted = kernelStream.query(EventQuery.matchAll()).stream().map(Event::data).toList();
		BoundedContextStarting starting = persisted.stream().filter(BoundedContextStarting.class::isInstance).map(BoundedContextStarting.class::cast).findFirst().orElseThrow();
		assertEquals(List.of(STRICT, DEFERRED), starting.businessRules());
		assertEquals(EnforcementLevel.DEFERRED_ENFORCEMENT, starting.businessRules().get(1).enforcementLevel());
		CommandRejected rejected = persisted.stream().filter(CommandRejected.class::isInstance).map(CommandRejected.class::cast).findFirst().orElseThrow();
		assertEquals(Verdict.BLOCKS, rejected.ruleJudgements().get(0).verdict());
		assertEquals("over", rejected.ruleJudgements().get(0).message());
	}

	@Test
	void eventsStoredBeforeTheRulesWereReportedStillRead ( ) {
		EventStreamId id = EventStreamId.forContext("rules").withPurpose("kernel");
		eventStorage().append(AppendCriteria.none(), id, List.of(
				new EventToStore(id, EventType.named("CommandRejected"),
						"{\"boundedContext\":\"rules\",\"command\":\"Withdraw\",\"reason\":\"no\",\"metrics\":null,\"slice\":null}", Tags.none(), null),
				new EventToStore(id, EventType.named("BoundedContextStarting"),
						"{\"boundedContext\":\"rules\",\"logical\":\"l\",\"physical\":\"p\",\"process\":\"x\",\"enabledFeatures\":[],\"disabledFeatures\":[],\"aspects\":null}", Tags.none(), null)));

		List<BoundedContextEvent> read = kernelStream().query(EventQuery.matchAll()).stream().map(Event::data).toList();

		assertEquals(List.of(), ((CommandRejected) read.get(0)).ruleJudgements(), "a rejection from before has no judgements");
		assertNull(((BoundedContextStarting) read.get(1)).businessRules(), "a start from before announced no rulebook, which is not an empty one");
	}

	private EventStream<BoundedContextEvent> kernelStream ( ) {
		return EventStore.on(eventStorage()).build()
				.getEventStream(EventStreamId.forContext("rules").withPurpose("kernel"), BoundedContextEvent.class);
	}

	private <T extends BoundedContextEvent> T only ( Class<T> type ) {
		List<T> found = received.stream().filter(type::isInstance).map(type::cast).toList();
		assertEquals(1, found.size(), () -> "expected one " + type.getSimpleName() + ", got " + received);
		return found.get(0);
	}

}
