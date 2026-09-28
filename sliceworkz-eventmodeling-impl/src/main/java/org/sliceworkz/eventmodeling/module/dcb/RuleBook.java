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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.OverrideRequest;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.RuleCheck;
import org.sliceworkz.eventmodeling.rules.RuleJudgement;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleViolation;

/**
 * The business rules one command execution checked, and the kernel's judgement of them.
 * <p>
 * A command {@linkplain #check checks} a rule and moves on; nothing is decided at that moment, because the
 * command may still take its decision on who may override it ({@link RuleCheck#overridableWhen}) and may
 * check further rules after it. The judgement is made from everything the command said, against the
 * {@link Overrides} it carries, when it is asked for: by the command itself through
 * {@link #ruleViolations()} (to record them in its events), and by the kernel once the command returned,
 * to decide whether the execution goes ahead. Both ask the same question of the same checks, which is what
 * keeps the recorded violations and the kernel's decision in agreement — provided nothing is checked or
 * decided after the command read them, which is why that is refused.
 */
final class RuleBook {

	private final Overrides overrides;
	private final List<Check> checks = new ArrayList<>();
	private boolean violationsRead;

	RuleBook ( Overrides overrides ) {
		this.overrides = ( overrides == null ) ? Overrides.none() : overrides;
	}

	RuleCheck check ( BusinessRule rule, boolean violated, String message ) {
		if ( rule == null ) {
			throw new IllegalArgumentException("check(...) needs the business rule it checks");
		}
		refuseAfterViolationsWereRead("check business rule '%s'".formatted(rule.id()));
		Check check = new Check(rule, violated, ( message == null || message.isBlank() ) ? rule.statement() : message);
		checks.add(check);
		return check;
	}

	/**
	 * @return the violations to record on the raised events: those whose judgement lets the execution go ahead
	 */
	List<RuleViolation> ruleViolations ( ) {
		violationsRead = true;
		return judgements().stream().flatMap(j -> j.recorded().stream()).toList();
	}

	/**
	 * @return the judgement of every violated rule, in the order the rules were checked
	 */
	List<RuleJudgement> judgements ( ) {
		List<RuleJudgement> judgements = new ArrayList<>();
		for ( Check check: checks ) {
			if ( check.violated ) {
				judgements.add(judge(check));
			}
		}
		return judgements;
	}

	/**
	 * @return the evaluation of the rules checked, as the execution would be judged on them
	 */
	Evaluation evaluation ( ) {
		return Evaluation.of(judgements());
	}

	private RuleJudgement judge ( Check check ) {
		BusinessRule rule = check.rule;
		EnforcementLevel level = rule.enforcementLevel();
		OverrideRequest request = overrides.requestFor(rule.id()).orElse(null);
		String explanation = ( request != null ) ? request.explanation() : null;

		return switch ( level ) {
			case STRICTLY_ENFORCED -> judgement(check, Verdict.BLOCKS, null, null);
			case DEFERRED_ENFORCEMENT -> judgement(check, Verdict.DEFERRED, null, null);
			case GUIDELINE -> judgement(check, Verdict.ADVISED, null, null);
			case PRE_AUTHORIZED_OVERRIDE, POST_JUSTIFIED_OVERRIDE, OVERRIDE_WITH_EXPLANATION -> {
				// Pre-authorization is a decision somebody has to take: a command silent about it authorized
				// nobody. The two other override levels are open to anyone who asks, unless the command narrowed them.
				boolean authorized = ( check.authorized != null ) ? check.authorized : level != EnforcementLevel.PRE_AUTHORIZED_OVERRIDE;
				if ( !authorized ) {
					String whyNot = ( check.whyNot != null ) ? check.whyNot : "no pre-authorization to override this rule";
					yield judgement(check, Verdict.BLOCKS, whyNot, null);
				}
				boolean accepted = request != null && ( !level.requiresExplanation() || request.hasExplanation() );
				yield accepted
					? judgement(check, Verdict.OVERRIDDEN, null, explanation)
					: judgement(check, Verdict.OVERRIDE_REQUIRED, null, null);
			}
		};
	}

	private static RuleJudgement judgement ( Check check, Verdict verdict, String reason, String explanation ) {
		BusinessRule rule = check.rule;
		return new RuleJudgement(rule.id(), rule.statement(), rule.enforcementLevel(), check.message, verdict, reason, explanation);
	}

	private void refuseAfterViolationsWereRead ( String what ) {
		if ( violationsRead ) {
			throw new IllegalStateException(("cannot %s after ruleViolations() was read: the violations recorded on the events must be the ones"
					+ " the execution is judged on, so check every business rule (and decide who may override it) before reading them").formatted(what));
		}
	}

	private final class Check implements RuleCheck {

		private final BusinessRule rule;
		private final boolean violated;
		private final String message;
		private Boolean authorized;
		private String whyNot;

		Check ( BusinessRule rule, boolean violated, String message ) {
			this.rule = rule;
			this.violated = violated;
			this.message = message;
		}

		@Override
		public RuleCheck overridableWhen ( boolean authorized, String whyNot ) {
			refuseAfterViolationsWereRead("decide who may override business rule '%s'".formatted(rule.id()));
			if ( this.authorized != null ) {
				throw new IllegalStateException("who may override business rule '%s' was already decided for this check".formatted(rule.id()));
			}
			if ( !authorized && ( whyNot == null || whyNot.isBlank() ) ) {
				throw new IllegalArgumentException("overridableWhen(false, ...) on business rule '%s' needs a reason to tell the actor".formatted(rule.id()));
			}
			this.authorized = authorized;
			this.whyNot = ( whyNot == null || whyNot.isBlank() ) ? null : whyNot;
			return this;
		}

		@Override
		public boolean violated ( ) {
			return violated;
		}

	}

}
