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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.RuleJudgement;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleViolation;

/**
 * Assertions on an {@link Evaluation}: what a command's business rules came to, either when the command
 * was only evaluated ({@code CommandTest.whenEvaluated(...)}) or when its execution was rejected on them
 * ({@code then().rulesViolated()}, the evaluation a {@code RuleViolationException} carries).
 * <pre>{@code
 * .whenEvaluated(new WithdrawCommand(account, amount, Overrides.none()))
 * .thenEvaluation()
 *     .needsOverrideOf(NO_OVERDRAFT)
 *     .judged(LARGE_WITHDRAWAL, Verdict.ADVISED);
 * }</pre>
 * Every assertion names the rules by the {@link BusinessRule} constants the command checks, so a renamed
 * rule id is a compile-time change in the test as much as in the command. Assertions chain; each one fails
 * with the whole evaluation in its message, since a verdict out of context rarely says why it was reached.
 */
public final class EvaluationAssertions {

	private final Evaluation evaluation;

	EvaluationAssertions ( Evaluation evaluation ) {
		this.evaluation = evaluation;
	}

	/**
	 * @return the evaluation, for an assertion these methods do not cover
	 */
	public Evaluation evaluation ( ) {
		return evaluation;
	}

	/**
	 * Asserts nothing stops the command: it would be executed as it stands.
	 *
	 * @return these assertions
	 */
	public EvaluationAssertions wouldSucceed ( ) {
		return outcome(Evaluation.Outcome.WOULD_SUCCEED);
	}

	/**
	 * Asserts the command would go ahead only with overrides, for exactly these rules — and that nothing blocks.
	 *
	 * @param rules the rules that need an override
	 * @return these assertions
	 */
	public EvaluationAssertions needsOverrideOf ( BusinessRule... rules ) {
		outcome(Evaluation.Outcome.NEEDS_OVERRIDE);
		return withVerdict(Verdict.OVERRIDE_REQUIRED, rules);
	}

	/**
	 * Asserts the command is blocked, by exactly these rules. Other rules may still need an override.
	 *
	 * @param rules the rules whose violation blocks for this actor
	 * @return these assertions
	 */
	public EvaluationAssertions blockedBy ( BusinessRule... rules ) {
		outcome(Evaluation.Outcome.BLOCKED);
		return withVerdict(Verdict.BLOCKS, rules);
	}

	/**
	 * Asserts the command threw a {@code BusinessException} with this message: the request makes no sense.
	 *
	 * @param expectedMessage the rejection's message
	 * @return these assertions
	 */
	public EvaluationAssertions rejected ( String expectedMessage ) {
		outcome(Evaluation.Outcome.REJECTED);
		assertEquals(expectedMessage, evaluation.rejection(), () -> "rejection message — " + render());
		return this;
	}

	/**
	 * Asserts the rule was violated and judged with this verdict.
	 *
	 * @param rule the rule
	 * @param verdict the verdict expected
	 * @return these assertions
	 */
	public EvaluationAssertions judged ( BusinessRule rule, Verdict verdict ) {
		RuleJudgement judgement = judgementOf(rule);
		assertEquals(verdict, judgement.verdict(), () -> "verdict on rule '%s' — %s".formatted(rule.id(), render()));
		return this;
	}

	/**
	 * Asserts the rule was violated, its level allows overrides, and the command decided this actor may not
	 * override it — for this reason.
	 *
	 * @param rule the rule
	 * @param reason what the actor is told
	 * @return these assertions
	 */
	public EvaluationAssertions notOverridable ( BusinessRule rule, String reason ) {
		judged(rule, Verdict.BLOCKS);
		assertEquals(reason, judgementOf(rule).reason(), () -> "reason on rule '%s' — %s".formatted(rule.id(), render()));
		return this;
	}

	/**
	 * Asserts the rule was checked and not violated — or not checked at all: either way, it is not judged.
	 *
	 * @param rule the rule
	 * @return these assertions
	 */
	public EvaluationAssertions notViolated ( BusinessRule rule ) {
		if ( evaluation.judgementOf(rule).isPresent() ) {
			fail("rule '%s' was not expected to be violated — %s".formatted(rule.id(), render()));
		}
		return this;
	}

	/**
	 * Asserts exactly these violations would be recorded on the raised events, in this order.
	 *
	 * @param expected the violations
	 * @return these assertions
	 */
	public EvaluationAssertions records ( RuleViolation... expected ) {
		assertEquals(List.of(expected), evaluation.recorded(), () -> "recorded violations — " + render());
		return this;
	}

	private EvaluationAssertions outcome ( Evaluation.Outcome expected ) {
		assertEquals(expected, evaluation.outcome(), () -> "evaluation outcome — " + render());
		return this;
	}

	private EvaluationAssertions withVerdict ( Verdict verdict, BusinessRule... rules ) {
		List<String> expected = Arrays.stream(rules).map(BusinessRule::id).sorted().toList();
		List<String> actual = evaluation.judgements().stream().filter(j -> j.verdict() == verdict).map(RuleJudgement::rule).sorted().toList();
		assertEquals(expected, actual, () -> "rules judged %s — %s".formatted(verdict, render()));
		return this;
	}

	private RuleJudgement judgementOf ( BusinessRule rule ) {
		return evaluation.judgementOf(rule).orElseGet(() -> fail("rule '%s' was expected to be violated — %s".formatted(rule.id(), render())));
	}

	private String render ( ) {
		String judgements = evaluation.judgements().isEmpty() ? "no rules violated"
				: evaluation.judgements().stream().map(RuleJudgement::toString).collect(Collectors.joining("; "));
		String rejection = evaluation.rejection() != null ? ", rejected: " + evaluation.rejection() : "";
		return "evaluation was %s%s: %s".formatted(evaluation.outcome(), rejection, judgements);
	}

}
