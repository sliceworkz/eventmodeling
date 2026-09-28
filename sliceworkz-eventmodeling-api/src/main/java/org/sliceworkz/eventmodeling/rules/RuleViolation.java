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

/**
 * A violation of a {@link BusinessRule} that the command was allowed to go ahead with, as it is
 * <strong>recorded in the payload of the events the command raises</strong> — the audit of who made which
 * exception, and the fact a follow-up works from.
 * <pre>{@code
 * record MoneyWithdrawn ( AccountId accountId, YearMonth month, BigDecimal amount, String description,
 *                         List<RuleViolation> ruleViolations ) implements BankingEvent { }
 *
 * result.raiseEvent(new MoneyWithdrawn(accountId, month, amount, description, context.ruleViolations()), tags);
 * }</pre>
 * Only violations that did not block are recorded: an override that was accepted, a deferred enforcement,
 * a guideline not followed. A violation that blocks rejects the command, so there is nothing to record it
 * on; a requested override of a rule that was not violated records nothing either.
 * <p>
 * Why in the payload, and not only in the {@link RuleTags rule tags} the kernel adds anyway: an explanation
 * is free text in the actor's words, which belongs in the event rather than in a tag, and a domain event
 * that says "this withdrawal was an exception to the overdraft rule" is the business fact itself. A domain
 * that wants an explicit event instead ({@code OverdraftApproved}) raises one; this record is the generic
 * shape for everything else. The actor is deliberately not repeated here: the kernel stores it in the
 * {@code x-actor} tag of every event, and {@code Tracing.readFrom(event).actor()} reads it back.
 * <p>
 * Like every record carried inside an event payload, the canonical constructor is lenient and never throws
 * (see WHERE-VALIDATIONS-GO.md): it is what Jackson calls on every read of history.
 *
 * @param rule the {@link BusinessRule#id() id} of the rule that was violated
 * @param enforcementLevel the level the rule was enforced at when the violation was recorded
 * @param disposition what became of the violation
 * @param message what the command said about this particular violation
 * @param explanation the actor's explanation, when an override came with one
 */
public record RuleViolation ( String rule, EnforcementLevel enforcementLevel, Disposition disposition, String message, String explanation ) {

	/**
	 * What became of a violation that did not block.
	 */
	public enum Disposition {

		/**
		 * Overridden, and done with: a {@link EnforcementLevel#PRE_AUTHORIZED_OVERRIDE pre-authorized}
		 * override, or one {@link EnforcementLevel#OVERRIDE_WITH_EXPLANATION with an explanation}.
		 */
		OVERRIDDEN,

		/**
		 * Overridden under {@link EnforcementLevel#POST_JUSTIFIED_OVERRIDE}: the override stands, and still
		 * has to be justified — a follow-up tracks it until it is.
		 */
		JUSTIFICATION_PENDING,

		/**
		 * Violated under {@link EnforcementLevel#DEFERRED_ENFORCEMENT}: allowed now, to be enforced later
		 * by a follow-up.
		 */
		ENFORCEMENT_DEFERRED,

		/**
		 * A {@link EnforcementLevel#GUIDELINE} that was not followed.
		 */
		GUIDELINE_NOT_FOLLOWED

	}

	/**
	 * Whether this violation was an override of a rule, whatever its level.
	 * <p>
	 * Named without a {@code get}/{@code is} prefix on purpose, like every derived method on this record:
	 * this record is stored inside event payloads, and a JSON mapper takes a prefixed method for a property —
	 * writing it into every stored event, and then refusing to read it back as a property no component has.
	 *
	 * @return {@code true} for {@link Disposition#OVERRIDDEN} and {@link Disposition#JUSTIFICATION_PENDING}
	 */
	public boolean overridden ( ) {
		return disposition == Disposition.OVERRIDDEN || disposition == Disposition.JUSTIFICATION_PENDING;
	}

	/**
	 * @param businessRule a rule
	 * @return whether this is a violation of that rule
	 */
	public boolean isOf ( BusinessRule businessRule ) {
		return businessRule.id().equals(rule);
	}

}
