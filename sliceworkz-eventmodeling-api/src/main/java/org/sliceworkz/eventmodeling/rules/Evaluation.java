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

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * What a command would do if it were executed now, and why: the answer to
 * {@link org.sliceworkz.eventmodeling.commands.CommandEvaluationCapability#evaluate evaluating} a command,
 * and the content of the {@link RuleViolationException} an execution is rejected with.
 * <p>
 * An evaluation runs the command exactly as an execution does — its decision models projected, its
 * {@code BusinessException}s thrown, its business rules checked and judged against the overrides it
 * carries — and stops short of the append. Nothing is stored, no idempotency key is spent. It is a
 * <em>preview</em>: the facts may change between an evaluation and the execution that follows it, and the
 * execution judges again, on the facts as they are then.
 *
 * @param outcome the overall answer
 * @param rejection the message of the {@code BusinessException} the command threw, when the outcome is
 *                  {@link Outcome#REJECTED}; {@code null} otherwise
 * @param judgements one judgement per violated rule, in the order the command checked them — including the
 *                   ones checked before a rejection
 */
public record Evaluation ( Outcome outcome, String rejection, List<RuleJudgement> judgements ) implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * The overall answer of an evaluation, worst first.
	 */
	public enum Outcome {

		/**
		 * The command threw a {@code BusinessException}: the request makes no sense ("the account does not
		 * exist"), and no override can change that. {@link #rejection()} says why.
		 */
		REJECTED,

		/**
		 * At least one violation {@link RuleJudgement.Verdict#BLOCKS blocks} for this actor.
		 */
		BLOCKED,

		/**
		 * Nothing blocks, but at least one violation needs an override this actor may give: execute again
		 * with the {@link Overrides} for the {@link Evaluation#overridesRequired() rules that need one}.
		 */
		NEEDS_OVERRIDE,

		/**
		 * The command would be executed: no violation stops it. There may still be advice, deferred
		 * enforcements and accepted overrides among the {@link Evaluation#judgements() judgements}.
		 */
		WOULD_SUCCEED

	}

	public Evaluation {
		judgements = ( judgements == null ) ? List.of() : List.copyOf(judgements);
	}

	/**
	 * The evaluation of a command that ran to its end.
	 *
	 * @param judgements the judgement of every violated rule
	 * @return the evaluation, its outcome derived from the judgements
	 */
	public static Evaluation of ( List<RuleJudgement> judgements ) {
		Outcome outcome = Outcome.WOULD_SUCCEED;
		for ( RuleJudgement judgement: judgements ) {
			if ( judgement.verdict() == RuleJudgement.Verdict.BLOCKS ) {
				outcome = Outcome.BLOCKED;
			} else if ( judgement.verdict() == RuleJudgement.Verdict.OVERRIDE_REQUIRED && outcome == Outcome.WOULD_SUCCEED ) {
				outcome = Outcome.NEEDS_OVERRIDE;
			}
		}
		return new Evaluation(outcome, null, judgements);
	}

	/**
	 * The evaluation of a command that threw a {@code BusinessException}.
	 *
	 * @param rejection the exception's message
	 * @param judgements the judgements of the rules checked before it was thrown
	 * @return the evaluation, {@link Outcome#REJECTED}
	 */
	public static Evaluation rejected ( String rejection, List<RuleJudgement> judgements ) {
		return new Evaluation(Outcome.REJECTED, rejection, judgements);
	}

	/**
	 * @return whether the command would be executed as it stands
	 */
	public boolean wouldSucceed ( ) {
		return outcome == Outcome.WOULD_SUCCEED;
	}

	/**
	 * @return the judgements of the violations that stop the execution: blocking ones and ones needing an override
	 */
	public List<RuleJudgement> stopping ( ) {
		return judgements.stream().filter(j -> j.verdict().stopsExecution()).toList();
	}

	/**
	 * @return the judgements of the violations this actor may override and has not yet — the checkboxes to show
	 */
	public List<RuleJudgement> overridesRequired ( ) {
		return judgements.stream().filter(j -> j.verdict() == RuleJudgement.Verdict.OVERRIDE_REQUIRED).toList();
	}

	/**
	 * @param rule a rule
	 * @return its judgement, if it was violated
	 */
	public Optional<RuleJudgement> judgementOf ( BusinessRule rule ) {
		return judgementOf(rule.id());
	}

	/**
	 * @param ruleId the id of a rule
	 * @return its judgement, if it was violated
	 */
	public Optional<RuleJudgement> judgementOf ( String ruleId ) {
		return judgements.stream().filter(j -> j.rule().equals(ruleId)).findFirst();
	}

	/**
	 * @return the violations that would be recorded on the raised events
	 */
	public List<RuleViolation> recorded ( ) {
		return judgements.stream().flatMap(j -> j.recorded().stream()).toList();
	}

}
