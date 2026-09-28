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
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The kernel's judgement of one violated {@link BusinessRule}, as a front end needs it to render the
 * violation: an error, an override checkbox (with or without a text field), or a hint.
 * <p>
 * Judgements are what {@link Evaluation} lists — both the answer to evaluating a command and the content of
 * the {@link RuleViolationException} that rejects an execution — so a front end has one shape to render
 * whether it checked beforehand or just submitted. Rules that were checked and <em>not</em> violated are
 * not judged and not listed.
 * <p>
 * Rendered as JSON — what a check endpoint answers — it carries its components plus {@code overridable} and
 * {@code explanationRequired}, the two derived facts a front end lays its widgets out by.
 *
 * @param rule the rule's {@link BusinessRule#id() id}: the key to send back in an {@link OverrideRequest}
 * @param statement the rule as the business states it
 * @param enforcementLevel the level the rule is enforced at
 * @param message what the command said about this violation
 * @param verdict what the violation means for the execution
 * @param reason why an override is not possible for this actor — set when the level allows overrides but
 *               the command's {@link RuleCheck#overridableWhen} decision said no; {@code null} otherwise
 * @param explanation the explanation that came with an accepted override, if any
 */
@JsonIgnoreProperties(ignoreUnknown = true)	// the two derived properties above, on the way back in
public record RuleJudgement ( String rule, String statement, EnforcementLevel enforcementLevel, String message,
		Verdict verdict, String reason, String explanation ) implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * What a violation means for the command execution.
	 */
	public enum Verdict {

		/**
		 * The violation rejects the execution and no override can change that for this actor: the rule is
		 * strictly enforced, or the command decided this actor may not override it ({@link #reason()}).
		 * A front end renders an error.
		 */
		BLOCKS,

		/**
		 * The violation rejects the execution unless it is overridden, and this actor may override it: send
		 * an {@link OverrideRequest} for the rule — with an explanation when {@link #explanationRequired()}.
		 * A front end renders a checkbox.
		 */
		OVERRIDE_REQUIRED,

		/**
		 * An override was requested and accepted: the execution goes ahead and records the violation.
		 */
		OVERRIDDEN,

		/**
		 * {@link EnforcementLevel#DEFERRED_ENFORCEMENT}: the execution goes ahead, and the rule is enforced
		 * later.
		 */
		DEFERRED,

		/**
		 * A {@link EnforcementLevel#GUIDELINE} not followed: the execution goes ahead. A front end renders a
		 * hint.
		 */
		ADVISED;

		/**
		 * @return whether a violation with this verdict stops the execution
		 */
		public boolean stopsExecution ( ) {
			return this == BLOCKS || this == OVERRIDE_REQUIRED;
		}

	}

	/**
	 * @return whether this actor can make the violation go away by overriding it — it needs an override, or
	 *         already has one
	 */
	@JsonProperty("overridable")
	public boolean overridable ( ) {
		return verdict == Verdict.OVERRIDE_REQUIRED || verdict == Verdict.OVERRIDDEN;
	}

	/**
	 * @return whether an override of this rule is only accepted with an explanation
	 */
	@JsonProperty("explanationRequired")
	public boolean explanationRequired ( ) {
		return enforcementLevel.requiresExplanation();
	}

	/**
	 * @param businessRule a rule
	 * @return whether this is the judgement of that rule
	 */
	public boolean isOf ( BusinessRule businessRule ) {
		return businessRule.id().equals(rule);
	}

	/**
	 * The violation as it is recorded on the raised events, for a verdict that lets the execution go ahead.
	 *
	 * @return the recorded violation; empty for a verdict that stops the execution
	 */
	public Optional<RuleViolation> recorded ( ) {
		RuleViolation.Disposition disposition = switch ( verdict ) {
			case BLOCKS, OVERRIDE_REQUIRED -> null;
			case OVERRIDDEN -> enforcementLevel == EnforcementLevel.POST_JUSTIFIED_OVERRIDE
					? RuleViolation.Disposition.JUSTIFICATION_PENDING
					: RuleViolation.Disposition.OVERRIDDEN;
			case DEFERRED -> RuleViolation.Disposition.ENFORCEMENT_DEFERRED;
			case ADVISED -> RuleViolation.Disposition.GUIDELINE_NOT_FOLLOWED;
		};
		return disposition == null ? Optional.empty() : Optional.of(new RuleViolation(rule, enforcementLevel, disposition, message, explanation));
	}

	/**
	 * Renders the judgement for a log line or an exception message.
	 */
	@Override
	public String toString ( ) {
		String why = ( reason != null ) ? " (" + reason + ")" : "";
		return "%s [%s, %s]: %s%s".formatted(rule, enforcementLevel, verdict, message, why);
	}

}
