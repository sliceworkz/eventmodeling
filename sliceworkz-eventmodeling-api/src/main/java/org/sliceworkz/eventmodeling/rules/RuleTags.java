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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The tags the kernel puts on every event a command raises when that command went ahead with a
 * {@link RuleViolation}, and the helpers to query for them.
 * <p>
 * The violation itself is in the payload (see {@link RuleViolation}); the tags are what make it
 * <em>queryable</em> without knowing the payload — by a decision model counting an actor's overrides, by a
 * todo list collecting what still has to be justified or enforced, by an auditor. They sit with the other
 * tags the framework writes, under the {@code x-} prefix, and carry the rule's {@link BusinessRule#id() id}
 * as their value:
 * <table>
 * <caption>Rule tags per disposition</caption>
 * <tr><th>Disposition</th><th>Tags</th></tr>
 * <tr><td>{@code OVERRIDDEN}</td><td>{@code x-rule-overridden:<rule>}</td></tr>
 * <tr><td>{@code JUSTIFICATION_PENDING}</td><td>{@code x-rule-overridden:<rule>}, {@code x-rule-justification-pending:<rule>}</td></tr>
 * <tr><td>{@code ENFORCEMENT_DEFERRED}</td><td>{@code x-rule-deferred:<rule>}</td></tr>
 * <tr><td>{@code GUIDELINE_NOT_FOLLOWED}</td><td>{@code x-rule-not-followed:<rule>}</td></tr>
 * </table>
 * Every override, whatever its level, carries {@code x-rule-overridden}, so "how many exceptions to this rule
 * did this actor make" is one tag query — the actor being the {@code x-actor} tag the kernel writes on every
 * event anyway:
 * <pre>{@code
 * EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), RuleTags.overriddenBy(actor, NO_OVERDRAFT))
 * }</pre>
 * Tag matching is containment, so the extra tags change no existing query and no consistency boundary. The
 * tags are written for what the payload records and are never removed: a justification pending stays tagged
 * as pending after it was justified, and the follow-up that tracks it subtracts the justification event, as
 * any todo list subtracts the events that finish its items.
 */
public final class RuleTags {

	/** The key of the tag on every override, whatever its level. */
	public static final String OVERRIDDEN = "x-rule-overridden";

	/** The key of the tag on an override under {@link EnforcementLevel#POST_JUSTIFIED_OVERRIDE}. */
	public static final String JUSTIFICATION_PENDING = "x-rule-justification-pending";

	/** The key of the tag on a violation under {@link EnforcementLevel#DEFERRED_ENFORCEMENT}. */
	public static final String DEFERRED = "x-rule-deferred";

	/** The key of the tag on a {@link EnforcementLevel#GUIDELINE} not followed. */
	public static final String NOT_FOLLOWED = "x-rule-not-followed";

	/**
	 * The key of the tag on an event that justifies a post-justified override made earlier. Its value is
	 * {@code <rule>@<event id>}, naming the override and the event it was recorded on, so the pending
	 * override and its justification pair up exactly — an event id is a UUID, and never holds an {@code @}.
	 */
	public static final String JUSTIFIED = "x-rule-justified";

	/**
	 * The key of the tag on an event that enforces a deferred violation recorded earlier; its value is
	 * {@code <rule>@<event id>}, as for {@link #JUSTIFIED}.
	 */
	public static final String ENFORCED = "x-rule-enforced";

	private RuleTags ( ) { }

	/**
	 * @param rule a rule
	 * @return the tag on every event raised with an override of the rule
	 */
	public static Tag overridden ( BusinessRule rule ) {
		return Tag.of(OVERRIDDEN, rule.id());
	}

	/**
	 * @param rule a rule
	 * @return the tag on every event raised with an override of the rule that still has to be justified
	 */
	public static Tag justificationPending ( BusinessRule rule ) {
		return Tag.of(JUSTIFICATION_PENDING, rule.id());
	}

	/**
	 * @param rule a rule
	 * @return the tag on every event raised with a deferred enforcement of the rule
	 */
	public static Tag deferred ( BusinessRule rule ) {
		return Tag.of(DEFERRED, rule.id());
	}

	/**
	 * @param rule a guideline
	 * @return the tag on every event raised without following the guideline
	 */
	public static Tag notFollowed ( BusinessRule rule ) {
		return Tag.of(NOT_FOLLOWED, rule.id());
	}

	/**
	 * @param rule the rule the event was an exception to
	 * @param event the event that recorded the override still to be justified
	 * @return the tag on every event raised with the justification of that override
	 */
	public static Tag justified ( BusinessRule rule, EventId event ) {
		return Tag.of(JUSTIFIED, link(rule.id(), event.value()));
	}

	/**
	 * @param rule the rule whose enforcement was deferred
	 * @param event the event that recorded the deferred enforcement
	 * @return the tag on every event raised with the enforcement of that violation
	 */
	public static Tag enforced ( BusinessRule rule, EventId event ) {
		return Tag.of(ENFORCED, link(rule.id(), event.value()));
	}

	/**
	 * The value of a {@link #JUSTIFIED} or {@link #ENFORCED} tag: the rule and the event it follows up.
	 *
	 * @param rule the rule id
	 * @param event the event id
	 * @return {@code <rule>@<event>}
	 */
	public static String link ( String rule, String event ) {
		return rule + "@" + event;
	}

	/**
	 * The tags the kernel adds to every event of an append that follows these violations up.
	 *
	 * @param followUps the follow-ups the command made
	 * @return their tags; none for no follow-ups
	 */
	public static Tags ofFollowUps ( List<RuleFollowUp> followUps ) {
		Set<Tag> tags = new HashSet<>();
		for ( RuleFollowUp followUp: followUps ) {
			String key = ( followUp.kind() == RuleFollowUp.Kind.JUSTIFIED ) ? JUSTIFIED : ENFORCED;
			tags.add(Tag.of(key, link(followUp.rule(), followUp.event())));
		}
		return tags.isEmpty() ? Tags.none() : new Tags(tags);
	}

	/**
	 * The tags that select the overrides of a rule by one actor.
	 *
	 * @param actor the actor, as the tracing named it
	 * @param rule the rule
	 * @return the actor's tag and the rule's override tag
	 */
	public static Tags overriddenBy ( String actor, BusinessRule rule ) {
		return Tags.of(Tracing.actorTag(actor), overridden(rule));
	}

	/**
	 * The tags the kernel adds to every event of an append that went ahead with these violations.
	 *
	 * @param violations the recorded violations
	 * @return their tags; none for no violations
	 */
	public static Tags of ( List<RuleViolation> violations ) {
		Set<Tag> tags = new HashSet<>();
		for ( RuleViolation violation: violations ) {
			switch ( violation.disposition() ) {
				case OVERRIDDEN -> tags.add(Tag.of(OVERRIDDEN, violation.rule()));
				case JUSTIFICATION_PENDING -> {
					tags.add(Tag.of(OVERRIDDEN, violation.rule()));
					tags.add(Tag.of(JUSTIFICATION_PENDING, violation.rule()));
				}
				case ENFORCEMENT_DEFERRED -> tags.add(Tag.of(DEFERRED, violation.rule()));
				case GUIDELINE_NOT_FOLLOWED -> tags.add(Tag.of(NOT_FOLLOWED, violation.rule()));
			}
		}
		return tags.isEmpty() ? Tags.none() : new Tags(tags);
	}

}
