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
 * One override a caller asks for: "I know rule {@code rule} is violated, go ahead anyway" — with the
 * explanation an {@link EnforcementLevel#OVERRIDE_WITH_EXPLANATION} rule insists on, and any other
 * override level records when it is given.
 * <p>
 * A request is not a grant. It is judged by the kernel against the rule's level and, for a
 * {@link EnforcementLevel#PRE_AUTHORIZED_OVERRIDE}, against the command's own decision on whether this
 * actor may override ({@link RuleCheck#overridableWhen}). A request for a rule that turns out not to be
 * violated is ignored and records nothing, so a front end may simply send back every box the user ticked.
 *
 * @param rule the {@link BusinessRule#id() id} of the rule to override
 * @param explanation why, in the actor's words; {@code null} when none was given
 */
public record OverrideRequest ( String rule, String explanation ) {

	public OverrideRequest {
		if ( rule == null || rule.isBlank() ) {
			throw new IllegalArgumentException("an override request names the rule it overrides");
		}
		rule = rule.strip();
		explanation = ( explanation == null || explanation.isBlank() ) ? null : explanation.strip();
	}

	/**
	 * @param rule the rule to override
	 * @return a request without explanation
	 */
	public static OverrideRequest of ( String rule ) {
		return new OverrideRequest(rule, null);
	}

	/**
	 * @param rule the rule to override
	 * @param explanation why
	 * @return a request with an explanation
	 */
	public static OverrideRequest of ( String rule, String explanation ) {
		return new OverrideRequest(rule, explanation);
	}

	/**
	 * @return whether the request carries a (non-blank) explanation
	 */
	public boolean hasExplanation ( ) {
		return explanation != null;
	}

}
