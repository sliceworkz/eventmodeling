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
 * The follow-up of a violation a command went ahead with: the justification of a
 * {@link EnforcementLevel#POST_JUSTIFIED_OVERRIDE post-justified} override, or the enforcement of a
 * {@link EnforcementLevel#DEFERRED_ENFORCEMENT deferred} one, made afterwards by another command.
 * <p>
 * Handed out by {@link org.sliceworkz.eventmodeling.commands.CommandContext#justifies} and
 * {@link org.sliceworkz.eventmodeling.commands.CommandContext#enforces}, for the command to record in the
 * payload of the event it raises — the counterpart of the {@link RuleViolation}s the original event recorded.
 * The payload is where the note belongs, being free text; the kernel records the link itself as a tag on
 * every event of the append ({@link RuleTags#justified}, {@link RuleTags#enforced}), so the pairing of an
 * obligation and what settled it is queryable without knowing any payload.
 * <p>
 * A payload record like every other, so its canonical constructor is lenient and never throws, and its
 * derived methods carry no {@code get}/{@code is} prefix.
 *
 * @param rule the id of the rule the original event was an exception to
 * @param event the id of the event that recorded the violation, the one this follows up
 * @param kind whether this justifies an override or enforces a deferred violation
 * @param note the justification or what was done to enforce, in the actor's words; may be null
 */
public record RuleFollowUp ( String rule, String event, Kind kind, String note ) {

	/**
	 * What a follow-up settles.
	 */
	public enum Kind {

		/** Justifies a {@link RuleViolation.Disposition#JUSTIFICATION_PENDING post-justified override}. */
		JUSTIFIED,

		/** Enforces a {@link RuleViolation.Disposition#ENFORCEMENT_DEFERRED deferred enforcement}. */
		ENFORCED

	}

	/**
	 * @param businessRule a rule
	 * @return whether this is a follow-up of that rule
	 */
	public boolean isOf ( BusinessRule businessRule ) {
		return businessRule.id().equals(rule);
	}

}
