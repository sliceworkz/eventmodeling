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
 * A behavioral business rule, in the SBVR sense: a statement of what people ought to do, which they can
 * nevertheless do otherwise, together with the {@link EnforcementLevel} that says what happens when they do.
 * <pre>{@code
 * static final BusinessRule NO_OVERDRAFT = BusinessRule.of(
 *         "no-overdraft", "A withdrawal must not make the balance of the account negative.")
 *     .enforcedAt(EnforcementLevel.PRE_AUTHORIZED_OVERRIDE);
 * }</pre>
 * A command checks it on its decision models with
 * {@link org.sliceworkz.eventmodeling.commands.CommandContext#check(BusinessRule, boolean, String)}; the
 * kernel judges the violation against the level after the command has run.
 *
 * <h2>What is not a business rule here</h2>
 * A request that makes no sense — the account does not exist, the period is already closed — is not a
 * rule to report with an enforcement level; it is a {@code BusinessException}, thrown straight away. SBVR
 * calls those <em>definitional</em> rules: they describe what can be true at all, and no override can make
 * a withdrawal from a non-existing account meaningful. Keep {@code BusinessException.when(...)} for those.
 *
 * <h2>Identity</h2>
 * <ul>
 * <li>{@link #id()} is <strong>wire format</strong>: it keys the override a front end sends back, it is
 *     stored in every {@link RuleViolation} and it is the value of the {@link RuleTags rule tags} the kernel
 *     puts on the raised events. Rename it and history no longer matches — treat it like an event type
 *     name. It must be non-blank and contain no whitespace; a short kebab-case name reads best in a tag
 *     ({@code no-overdraft}).</li>
 * <li>{@link #statement()} is the rule in the language of the business, shown to the user who violates
 *     it. It is not stored: history records which rule was violated and what the command said about the
 *     violation, and the statement may be reworded without touching either.</li>
 * <li>{@link #enforcementLevel()} is deliberately beside the identity rather than part of it (see
 *     {@link EnforcementLevel}); two rules with the same id are the same rule, whatever their levels.</li>
 * </ul>
 *
 * @param id the rule's stable identifier
 * @param statement the rule as the business states it
 * @param enforcementLevel how strictly the rule is enforced
 */
public record BusinessRule ( String id, String statement, EnforcementLevel enforcementLevel ) {

	public BusinessRule {
		if ( id == null || id.isBlank() ) {
			throw new IllegalArgumentException("a business rule needs an id");
		}
		if ( id.chars().anyMatch(Character::isWhitespace) ) {
			throw new IllegalArgumentException("business rule id '%s' contains whitespace: it is stored as a tag value and sent back by front ends, so keep it a single token such as 'no-overdraft'".formatted(id));
		}
		if ( statement == null || statement.isBlank() ) {
			throw new IllegalArgumentException("business rule '%s' needs a statement".formatted(id));
		}
		if ( enforcementLevel == null ) {
			throw new IllegalArgumentException("business rule '%s' needs an enforcement level".formatted(id));
		}
	}

	/**
	 * A rule that is {@link EnforcementLevel#STRICTLY_ENFORCED strictly enforced} until
	 * {@link #enforcedAt(EnforcementLevel) told otherwise} — a rule nobody decided to relax is enforced.
	 *
	 * @param id the rule's stable identifier
	 * @param statement the rule as the business states it
	 * @return the rule, strictly enforced
	 */
	public static BusinessRule of ( String id, String statement ) {
		return new BusinessRule(id, statement, EnforcementLevel.STRICTLY_ENFORCED);
	}

	/**
	 * The same rule at another enforcement level.
	 *
	 * @param enforcementLevel the level to enforce it at
	 * @return the rule at that level
	 */
	public BusinessRule enforcedAt ( EnforcementLevel enforcementLevel ) {
		return new BusinessRule(id, statement, enforcementLevel);
	}

	/**
	 * Two rules are the same rule when their ids are equal: the level is policy and the statement is
	 * wording, neither of them identity.
	 */
	@Override
	public boolean equals ( Object other ) {
		return other instanceof BusinessRule rule && rule.id.equals(id);
	}

	@Override
	public int hashCode ( ) {
		return id.hashCode();
	}

}
