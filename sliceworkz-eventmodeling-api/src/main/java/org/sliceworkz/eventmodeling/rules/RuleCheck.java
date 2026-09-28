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
 * What a command gets back from checking a {@link BusinessRule}: the handle on which it takes its own
 * decision about who may override a violation.
 * <pre>{@code
 * context.check(NO_OVERDRAFT, period.balance().compareTo(amount) < 0, "Balance would go negative")
 *        .overridableWhen(overridesToday.count() < 3, "You already made 3 overdraft exceptions today");
 * }</pre>
 * <h2>Why the decision is the command's</h2>
 * Whether an actor may override a rule is, more often than not, a business rule of its own, and a subtle
 * one: a role that was granted through events, a maximum number of exceptions per actor per day, a
 * deviation small enough for a junior and too large for anyone but a manager. All of that needs history,
 * and history is what a command has in its decision models. Taking the decision there puts it inside the
 * command's consistency boundary: two overrides racing past a quota of three per day cannot both succeed,
 * because the second append conflicts and its retry re-decides on a count that includes the first. A
 * policy configured beside the context could never promise that. It is also testable with the same
 * given/when/then as the rule itself, and a command that is only {@linkplain
 * org.sliceworkz.eventmodeling.commands.CommandEvaluationCapability evaluated} reports it — so a front end
 * shows the override checkbox only to an actor who may actually tick it.
 * <p>
 * The actor is {@link org.sliceworkz.eventmodeling.commands.OutboundCommandContext#actor()}, read from the
 * tracing the command was executed with — the same value the kernel stores in the {@code x-actor} tag of
 * every event it appends, so the actor the decision is taken for and the actor history records are one.
 *
 * <h2>Defaults, when the command takes no decision</h2>
 * <ul>
 * <li>{@link EnforcementLevel#PRE_AUTHORIZED_OVERRIDE}: nobody is authorized. Pre-authorization is a
 *     decision somebody has to take, and a command silent about it has not taken one.</li>
 * <li>{@link EnforcementLevel#POST_JUSTIFIED_OVERRIDE} and {@link EnforcementLevel#OVERRIDE_WITH_EXPLANATION}:
 *     anybody who asks (and explains) may override; {@code overridableWhen} narrows that.</li>
 * <li>Every other level: {@code overridableWhen} has nothing to decide, and calling it is harmless.</li>
 * </ul>
 * Call it at most once per check, and before reading
 * {@link org.sliceworkz.eventmodeling.commands.CommandContext#ruleViolations()}: what is recorded on the
 * events has to be what was judged.
 */
public interface RuleCheck {

	/**
	 * Decides whether the actor executing the command may override this violation.
	 *
	 * @param authorized whether the actor may override
	 * @param whyNot what to tell an actor who may not — shown next to the violation instead of an override
	 *               checkbox; required, since "you may not" alone helps nobody
	 * @return this check
	 */
	RuleCheck overridableWhen ( boolean authorized, String whyNot );

	/**
	 * @return whether the rule was violated at all
	 */
	boolean violated ( );

}
