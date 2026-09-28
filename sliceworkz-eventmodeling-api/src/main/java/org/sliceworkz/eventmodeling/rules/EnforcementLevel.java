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
 * How strictly a {@link BusinessRule} is enforced: the SBVR <em>enforcement level</em> of a behavioral
 * business rule.
 * <p>
 * SBVR (the OMG's Semantics of Business Vocabulary and Business Rules) separates two kinds of rule. A
 * <em>definitional</em> rule says what can be true at all ("each account has exactly one currency"), and
 * breaking it is not a violation to report: the request makes no sense, which a command answers with a
 * {@link org.sliceworkz.eventmodeling.commands.BusinessException} straight away. A <em>behavioral</em> rule
 * says what people ought to do ("a withdrawal must not make the balance negative"), and people can do
 * otherwise — so it carries an enforcement level, saying what happens when they do. The levels below are
 * the ones commonly listed with SBVR, and the framework acts on each of them.
 * <p>
 * The level is a policy decision, deliberately kept out of the rule's meaning: a bank may decide next month
 * that overdrafts are strictly enforced after all, and the rule "a withdrawal must not make the balance
 * negative" is the same rule before and after. That is why a {@link BusinessRule} carries its id and
 * statement as its identity and its level beside them, and why every {@link RuleViolation} recorded on an
 * event names the level that was in force when it was recorded.
 *
 * <h2>What each level does</h2>
 * <table>
 * <caption>Enforcement levels and their effect on a command execution</caption>
 * <tr><th>Level</th><th>A violation…</th><th>Recorded on the raised events as</th></tr>
 * <tr><td>{@link #STRICTLY_ENFORCED}</td><td>blocks, always</td><td>—</td></tr>
 * <tr><td>{@link #DEFERRED_ENFORCEMENT}</td><td>never blocks; enforced later</td>
 *     <td>{@link RuleViolation.Disposition#ENFORCEMENT_DEFERRED}</td></tr>
 * <tr><td>{@link #PRE_AUTHORIZED_OVERRIDE}</td><td>blocks unless an override is requested <em>and</em> the
 *     command authorized this actor through {@link RuleCheck#overridableWhen}</td>
 *     <td>{@link RuleViolation.Disposition#OVERRIDDEN}</td></tr>
 * <tr><td>{@link #POST_JUSTIFIED_OVERRIDE}</td><td>blocks unless an override is requested</td>
 *     <td>{@link RuleViolation.Disposition#JUSTIFICATION_PENDING}</td></tr>
 * <tr><td>{@link #OVERRIDE_WITH_EXPLANATION}</td><td>blocks unless an override is requested with an
 *     explanation</td><td>{@link RuleViolation.Disposition#OVERRIDDEN}, explanation included</td></tr>
 * <tr><td>{@link #GUIDELINE}</td><td>never blocks; reported as advice</td>
 *     <td>{@link RuleViolation.Disposition#GUIDELINE_NOT_FOLLOWED}</td></tr>
 * </table>
 * <p>
 * The two levels that are enforced <em>after</em> the fact — deferred enforcement and post-justified
 * override — need no machinery beyond the recorded violation: the follow-up is an ordinary todo list over
 * the events carrying it, and an automation or a person acting on that list. See {@code BUSINESS-RULES.md}.
 */
public enum EnforcementLevel {

	/**
	 * A violation is prevented: the command is rejected and nobody can override it.
	 * <p>
	 * The difference with a {@code BusinessException} is not in the outcome but in what it is: a strictly
	 * enforced rule is a named behavioral rule with a statement, reported next to every other violation
	 * when a command is evaluated, and relaxing it later is a change of its level rather than of the code
	 * that checks it.
	 */
	STRICTLY_ENFORCED,

	/**
	 * The rule is strictly enforced, but not now: the command goes ahead, and the violation is recorded as
	 * {@link RuleViolation.Disposition#ENFORCEMENT_DEFERRED} for a follow-up — typically a todo list over
	 * the events carrying it, worked off by an automation — to enforce once it can.
	 */
	DEFERRED_ENFORCEMENT,

	/**
	 * Enforced, except for an actor who holds the authorization to override it before the fact. Which
	 * actor that is, is a decision of the command, taken through {@link RuleCheck#overridableWhen} on its
	 * own decision models — a role recorded in events, a quota of overrides per day, the size of the
	 * deviation. A command that takes no such decision authorizes nobody.
	 */
	PRE_AUTHORIZED_OVERRIDE,

	/**
	 * May be overridden, but the override has to be justified afterwards: the violation is recorded as
	 * {@link RuleViolation.Disposition#JUSTIFICATION_PENDING}, and an override that is never justified is a
	 * violation after all — which a follow-up (a todo list of pending justifications with a deadline)
	 * escalates. A command may still narrow who may override through {@link RuleCheck#overridableWhen};
	 * without that, anyone who requests the override gets it.
	 */
	POST_JUSTIFIED_OVERRIDE,

	/**
	 * May be overridden by an actor who explains why. The explanation travels with the override request
	 * and is recorded with the violation. A command may narrow who may override through
	 * {@link RuleCheck#overridableWhen}; without that, anyone who explains gets it.
	 */
	OVERRIDE_WITH_EXPLANATION,

	/**
	 * Suggested, not enforced: a violation never blocks, is reported as advice, and is recorded as
	 * {@link RuleViolation.Disposition#GUIDELINE_NOT_FOLLOWED}.
	 */
	GUIDELINE;

	/**
	 * Whether a violation of a rule at this level can be overridden at all — by someone, with the right
	 * request. Says nothing about whether this particular actor may: that is the command's decision.
	 *
	 * @return {@code true} for the three override levels
	 */
	public boolean isOverridable ( ) {
		return this == PRE_AUTHORIZED_OVERRIDE || this == POST_JUSTIFIED_OVERRIDE || this == OVERRIDE_WITH_EXPLANATION;
	}

	/**
	 * Whether an override at this level needs an explanation to be accepted.
	 *
	 * @return {@code true} for {@link #OVERRIDE_WITH_EXPLANATION}
	 */
	public boolean requiresExplanation ( ) {
		return this == OVERRIDE_WITH_EXPLANATION;
	}

	/**
	 * Whether a violation at this level lets the command go ahead without anybody asking for it.
	 *
	 * @return {@code true} for {@link #DEFERRED_ENFORCEMENT} and {@link #GUIDELINE}
	 */
	public boolean proceedsUnasked ( ) {
		return this == DEFERRED_ENFORCEMENT || this == GUIDELINE;
	}

}
