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
package org.sliceworkz.eventmodeling.commands;

import java.util.List;

import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.RuleCheck;
import org.sliceworkz.eventmodeling.rules.RuleFollowUp;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventstore.events.EventId;

/**
 * The execution context handed to a {@link Command} (and to a {@link CommandWithResult}).
 * <p>
 * Extends {@link OutboundCommandContext} with the one capability an {@link OutboundCommand} must not
 * have: {@link #decisionModels(DecisionModel...)}. A domain command appends to the same stream its
 * decision models are projected from, so the boundary they produce genuinely guards the append —
 * which is exactly what does not hold for an outbound append, and why the narrower context exists.
 * <p>
 * It is also where a command checks its {@link BusinessRule business rules}, for the same reason: a rule is
 * judged on decision models, and only a domain command has them. The shape is always the same — select
 * decision models, reject what makes no sense, check the rules, raise:
 * <pre>{@code
 * var period = new ActivePeriodDecisionModel(accountId);
 * var result = context.decisionModels(period);
 *
 * BusinessException.when(!period.accountExists(), "Account does not exist");      // makes no sense: reject
 *
 * context.check(NO_OVERDRAFT, period.balance().compareTo(amount) < 0, "Balance would go negative")
 *        .overridableWhen(overrides.count() < 3, "Daily maximum of 3 overdraft exceptions reached");
 *
 * result.raiseEvent(new MoneyWithdrawn(accountId, amount, context.ruleViolations()), tags);
 * }</pre>
 * See {@code BUSINESS-RULES.md} for the enforcement levels and what the kernel does with each.
 */
public interface CommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> extends OutboundCommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {

	CommandResult<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> decisionModels ( @SuppressWarnings("unchecked") DecisionModel<CONSUMED_EVENT_TYPE>... decisionModels );

	/**
	 * Checks a behavioral business rule: reports whether it is violated, and what to tell the actor if it
	 * is. <strong>Never throws</strong> — the kernel judges every violation after the command has run,
	 * against the rule's {@link org.sliceworkz.eventmodeling.rules.EnforcementLevel enforcement level}, the
	 * {@link org.sliceworkz.eventmodeling.rules.Overrides overrides} the command carries and the
	 * {@link RuleCheck#overridableWhen authorization} the command decides on. Judging afterwards is what lets
	 * a command report <em>every</em> violated rule at once rather than the first, and what lets the same
	 * command be {@linkplain CommandEvaluationCapability evaluated} without being executed.
	 * <p>
	 * A violation that stops the execution rejects it with a
	 * {@link org.sliceworkz.eventmodeling.rules.RuleViolationException} once the command returns, so the
	 * events it raised are never appended; one that lets it go ahead is recorded, through
	 * {@link #ruleViolations()} in the payload and through the rule tags the kernel adds. So a command checks
	 * its rules and raises its events as if everything were allowed — it does not need to branch on the
	 * outcome.
	 * <p>
	 * Check rules after {@link #decisionModels(DecisionModel...)}, on what the models say: a rule judged on
	 * facts outside the consistency boundary is judged on facts that may change underneath it. And check them
	 * before reading {@link #ruleViolations()}: a check after that read is refused with an
	 * {@code IllegalStateException}, since the events would record a judgement that was not final.
	 * <p>
	 * A request that makes no sense is not a rule to check here: throw a {@link BusinessException} for it.
	 *
	 * @param rule the rule
	 * @param violated whether the rule is violated by this execution
	 * @param message what to tell the actor about this violation — specific to it ("balance would go
	 *                negative by 20.00"), where the rule's statement is general; used only when violated
	 * @return the check, to decide on who may override it
	 */
	RuleCheck check ( BusinessRule rule, boolean violated, String message );

	/**
	 * The violations this command goes ahead with — accepted overrides, deferred enforcements, guidelines not
	 * followed — for the command to record in the payload of the events it raises. Judged on the rules checked
	 * so far, which is why every {@link #check} comes before this call.
	 * <p>
	 * A violation that stops the execution is not in the list: the execution is rejected, and the events
	 * carrying the list are never appended.
	 *
	 * @return the violations to record; empty when nothing was violated, or nothing that may go ahead
	 */
	List<RuleViolation> ruleViolations ( );

	/**
	 * Records that this execution justifies a {@link org.sliceworkz.eventmodeling.rules.EnforcementLevel#POST_JUSTIFIED_OVERRIDE
	 * post-justified override} made earlier: the second half of that enforcement level. The kernel tags every
	 * event of the append {@link org.sliceworkz.eventmodeling.rules.RuleTags#justified RuleTags.justified(rule, event)},
	 * which pairs the override with its justification for a follow-up read model and for an auditor alike;
	 * the returned {@link RuleFollowUp} is for the command to record in the payload of the event it raises,
	 * which is where the justification's text belongs.
	 * <p>
	 * Whether the event needs a justification, and whether it has one already, is the command's to decide on
	 * its decision models — asking for something the request cannot mean is a {@link BusinessException}, as
	 * everywhere. The kernel does not read the event again. An execution that makes a follow-up and raises
	 * nothing is refused with an {@code IllegalStateException}, since the link would be recorded nowhere.
	 *
	 * @param rule the rule the event was an exception to
	 * @param event the id of the event that recorded the override
	 * @param justification the justification, in the actor's words; may be null
	 * @return the follow-up, to record in the payload
	 */
	RuleFollowUp justifies ( BusinessRule rule, EventId event, String justification );

	/**
	 * Records that this execution enforces a {@link org.sliceworkz.eventmodeling.rules.EnforcementLevel#DEFERRED_ENFORCEMENT
	 * deferred enforcement} recorded earlier. The counterpart of {@link #justifies} for that level: the kernel tags
	 * every event of the append {@link org.sliceworkz.eventmodeling.rules.RuleTags#enforced RuleTags.enforced(rule, event)}.
	 *
	 * @param rule the rule whose enforcement was deferred
	 * @param event the id of the event that recorded the deferred enforcement
	 * @param note what was done to enforce it; may be null
	 * @return the follow-up, to record in the payload
	 */
	RuleFollowUp enforces ( BusinessRule rule, EventId event, String note );

}
