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
package org.sliceworkz.eventmodeling.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventstore.events.Tag;

/**
 * Pins the value types of the business rules api: a rule's identity and its validation, the overrides a
 * caller sends, the outcome an evaluation derives from its judgements, what a judgement records, the tags a
 * recorded violation is stored under, and that the rejection carrying all of it survives a process boundary.
 */
public class BusinessRulesTest {

	private static final BusinessRule RULE = BusinessRule.of("no-overdraft", "A withdrawal must not make the balance negative.");

	@Test
	void aRuleNobodyRelaxedIsStrictlyEnforced ( ) {
		assertEquals(EnforcementLevel.STRICTLY_ENFORCED, RULE.enforcementLevel());
		assertEquals(EnforcementLevel.GUIDELINE, RULE.enforcedAt(EnforcementLevel.GUIDELINE).enforcementLevel());
	}

	@Test
	void aRuleIsIdentifiedByItsIdAlone ( ) {
		BusinessRule relaxed = BusinessRule.of("no-overdraft", "Reworded.").enforcedAt(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE);
		assertEquals(RULE, relaxed);
		assertEquals(RULE.hashCode(), relaxed.hashCode());
		assertNotEquals(RULE, BusinessRule.of("other", "Other."));
	}

	@Test
	void aRuleIdIsOneTokenAndAStatementIsRequired ( ) {
		assertThrows(IllegalArgumentException.class, () -> BusinessRule.of(" ", "statement"));
		assertThrows(IllegalArgumentException.class, () -> BusinessRule.of("no overdraft", "statement"));
		assertThrows(IllegalArgumentException.class, () -> BusinessRule.of("id", " "));
		assertThrows(IllegalArgumentException.class, () -> new BusinessRule("id", "statement", null));
	}

	@Test
	void theLevelsSayWhatTheyAllow ( ) {
		assertEquals(Set.of(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, EnforcementLevel.POST_JUSTIFIED_OVERRIDE, EnforcementLevel.OVERRIDE_WITH_EXPLANATION),
				Set.of(EnforcementLevel.values()).stream().filter(EnforcementLevel::isOverridable).collect(java.util.stream.Collectors.toSet()));
		assertEquals(Set.of(EnforcementLevel.DEFERRED_ENFORCEMENT, EnforcementLevel.GUIDELINE),
				Set.of(EnforcementLevel.values()).stream().filter(EnforcementLevel::proceedsUnasked).collect(java.util.stream.Collectors.toSet()));
		assertTrue(EnforcementLevel.OVERRIDE_WITH_EXPLANATION.requiresExplanation());
		assertFalse(EnforcementLevel.POST_JUSTIFIED_OVERRIDE.requiresExplanation());
	}

	@Test
	void overridesNameEachRuleOnceAndNormaliseTheirExplanation ( ) {
		Overrides overrides = Overrides.of("a").with("b", "  why  ").with(RULE, " ");
		assertEquals(Optional.of(new OverrideRequest("b", "why")), overrides.requestFor("b"));
		assertFalse(overrides.requestFor("no-overdraft").orElseThrow().hasExplanation(), "a blank explanation is none");
		assertTrue(overrides.requestFor("c").isEmpty());
		assertThrows(IllegalArgumentException.class, () -> Overrides.of("a", "a"));
		assertThrows(IllegalArgumentException.class, () -> OverrideRequest.of(" "));
		assertTrue(new Overrides(null).isEmpty());
	}

	@Test
	void anEvaluationTakesTheWorstVerdictForItsOutcome ( ) {
		assertEquals(Evaluation.Outcome.WOULD_SUCCEED, Evaluation.of(List.of(judgement(Verdict.ADVISED), judgement(Verdict.OVERRIDDEN))).outcome());
		assertEquals(Evaluation.Outcome.NEEDS_OVERRIDE, Evaluation.of(List.of(judgement(Verdict.DEFERRED), judgement(Verdict.OVERRIDE_REQUIRED))).outcome());
		assertEquals(Evaluation.Outcome.BLOCKED, Evaluation.of(List.of(judgement(Verdict.BLOCKS), judgement(Verdict.OVERRIDE_REQUIRED))).outcome());
		assertEquals(Evaluation.Outcome.BLOCKED, Evaluation.of(List.of(judgement(Verdict.OVERRIDE_REQUIRED), judgement(Verdict.BLOCKS))).outcome());
		assertEquals(Evaluation.Outcome.WOULD_SUCCEED, Evaluation.of(List.of()).outcome());
	}

	@Test
	void onlyAVerdictThatGoesAheadIsRecorded ( ) {
		assertTrue(judgement(Verdict.BLOCKS).recorded().isEmpty());
		assertTrue(judgement(Verdict.OVERRIDE_REQUIRED).recorded().isEmpty());
		assertEquals(Disposition.OVERRIDDEN, judgement(Verdict.OVERRIDDEN).recorded().orElseThrow().disposition());
		assertEquals(Disposition.ENFORCEMENT_DEFERRED, judgement(Verdict.DEFERRED).recorded().orElseThrow().disposition());
		assertEquals(Disposition.GUIDELINE_NOT_FOLLOWED, judgement(Verdict.ADVISED).recorded().orElseThrow().disposition());

		RuleJudgement postJustified = new RuleJudgement("r", "s", EnforcementLevel.POST_JUSTIFIED_OVERRIDE, "m", Verdict.OVERRIDDEN, null, "because");
		assertEquals(new RuleViolation("r", EnforcementLevel.POST_JUSTIFIED_OVERRIDE, Disposition.JUSTIFICATION_PENDING, "m", "because"), postJustified.recorded().orElseThrow());
		assertTrue(postJustified.recorded().orElseThrow().overridden());
	}

	@Test
	void everyOverrideIsTaggedOverriddenAndAPendingJustificationToo ( ) {
		List<RuleViolation> violations = List.of(
				new RuleViolation("a", EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, Disposition.OVERRIDDEN, "m", null),
				new RuleViolation("b", EnforcementLevel.POST_JUSTIFIED_OVERRIDE, Disposition.JUSTIFICATION_PENDING, "m", null),
				new RuleViolation("c", EnforcementLevel.DEFERRED_ENFORCEMENT, Disposition.ENFORCEMENT_DEFERRED, "m", null),
				new RuleViolation("d", EnforcementLevel.GUIDELINE, Disposition.GUIDELINE_NOT_FOLLOWED, "m", null));

		assertEquals(Set.of(
				Tag.of(RuleTags.OVERRIDDEN, "a"),
				Tag.of(RuleTags.OVERRIDDEN, "b"), Tag.of(RuleTags.JUSTIFICATION_PENDING, "b"),
				Tag.of(RuleTags.DEFERRED, "c"),
				Tag.of(RuleTags.NOT_FOLLOWED, "d")),
				RuleTags.of(violations).tags());
		assertTrue(RuleTags.of(List.of()).tags().isEmpty());
		assertEquals(Set.of(Tag.of("x-actor", "alice"), Tag.of(RuleTags.OVERRIDDEN, "no-overdraft")), RuleTags.overriddenBy(" alice ", RULE).tags());
	}

	@Test
	void aRuleViolationExceptionSurvivesAProcessBoundaryWithItsEvaluation ( ) throws Exception {
		RuleViolationException original = new RuleViolationException(Evaluation.of(List.of(judgement(Verdict.OVERRIDE_REQUIRED))));

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try ( ObjectOutputStream out = new ObjectOutputStream(bytes) ) {
			out.writeObject(original);
		}
		RuleViolationException copy;
		try ( ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) ) {
			copy = (RuleViolationException) in.readObject();
		}

		assertEquals(original.getMessage(), copy.getMessage());
		assertEquals(original.evaluation(), copy.evaluation());
		assertTrue(copy.getMessage().startsWith("Business rules violated: r ["), copy.getMessage());
	}

	private static RuleJudgement judgement ( Verdict verdict ) {
		EnforcementLevel level = switch ( verdict ) {
			case BLOCKS -> EnforcementLevel.STRICTLY_ENFORCED;
			case OVERRIDE_REQUIRED, OVERRIDDEN -> EnforcementLevel.PRE_AUTHORIZED_OVERRIDE;
			case DEFERRED -> EnforcementLevel.DEFERRED_ENFORCEMENT;
			case ADVISED -> EnforcementLevel.GUIDELINE;
		};
		return new RuleJudgement("r", "statement", level, "message", verdict, null, null);
	}

}
