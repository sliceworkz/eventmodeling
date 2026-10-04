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
package org.sliceworkz.eventmodeling.module.policy;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.automation.PolicyRejectionHandling;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.commands.RetryPolicy;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;

/**
 * One domain event reacted to: the policy handed the event, and the command it answers with executed. The
 * one code path for it, run by a policy's processor and by the published {@code PolicyTest} harness alike,
 * so a test exercises exactly the key, the tracing and the duplicate and rejection handling a deployment runs.
 * <p>
 * The command is executed under the policy as actor and {@code policy} as channel, continuing the flow of
 * the event (its correlation id) and naming it as the cause of everything the command raises
 * ({@link Tracing#TAG_CAUSED_BY}). The event is handed over again whenever the processor did not get to
 * bookmark past it — a crash between the append and the bookmark, a failover overlap — and two things make
 * that harmless, so the first reaction stands:
 * <ul>
 * <li><b>A reaction leaves a trace that is looked for first.</b> Every event the command raised carries the
 *     policy as its actor and the event as its cause, so before reacting the reaction asks the domain stream
 *     for one such event — a tag lookup, one event at most. Found, the event was reacted to before, and it
 *     is answered {@link Outcome.AlreadyReacted} without executing anything. This holds whatever the command
 *     would decide the second time, including a different number of events.</li>
 * <li><b>The idempotency key covers the overlap the lookup cannot.</b> Two leaders reacting to the same event
 *     at once — a paused leader resuming past its lease — both find no trace. The command is executed with the
 *     key {@code policy:<policy name>@<event id>}, so the same events raised twice are a retry the store
 *     swallows, and different ones mixing stored keys with new ones are refused with an
 *     {@link IdempotencyKeyConflictException} — which, the key deriving from this policy and this event
 *     alone, can only mean the event was reacted to: answered {@link Outcome.AlreadyReacted} as well.</li>
 * </ul>
 * A reaction whose command raised nothing leaves no trace, so after a crash it is executed again, and
 * decides again; a command issued by a policy is written to be idempotent by its own rules for exactly
 * that reason.
 * <p>
 * An {@code OptimisticLockingException} is retried here, by re-executing the command, which re-decides. A
 * {@link BusinessException} is the command's "no": answered {@link Outcome.ReactionRejected} when the policy
 * was registered to skip rejections, rethrown — so the processor stalls on the event — when it was registered
 * to stall. Anything else is rethrown, and stalls the processor.
 */
public final class Reaction {

	private static final Logger LOGGER = LoggerFactory.getLogger(Reaction.class);

	/** The channel the commands a policy issues are traced under. */
	public static final String CHANNEL = "policy";

	private Reaction ( ) {
	}

	/**
	 * The idempotency key a policy executes its command for one event under.
	 *
	 * @param policyName the policy's name
	 * @param event the event reacted to
	 * @return {@code policy:<policy name>@<event id>}
	 */
	public static String idempotencyKey ( String policyName, Event<?> event ) {
		return "policy:" + policyName + "@" + event.reference().id().value();
	}

	/**
	 * The query finding what a policy raised in reaction to one event: tagged with the policy as actor and the
	 * event as cause. At most one is needed to know the event was reacted to.
	 *
	 * @param policyName the policy's name
	 * @param event the event reacted to
	 * @return the query, limited to one event
	 */
	public static EventQuery raisedInReactionTo ( String policyName, Event<?> event ) {
		return EventQuery.forTags(Tags.of(
				Tracing.actorTag(policyName),
				Tag.of(Tracing.TAG_CAUSED_BY, event.reference().id().value()))).limit(1);
	}

	/**
	 * Reacts to one domain event.
	 *
	 * @param policy the policy
	 * @param policyName the name it is known by, {@link Policy#policyName()}
	 * @param event the domain event, as stored
	 * @param onRejection what a business rejection of the command does
	 * @param instance the instance reacting, stamped on what the command raises
	 * @param executor what executes the command
	 * @param domainStream the domain stream the command appends to, where a previous reaction left its trace
	 * @return what the reaction answered
	 * @throws BusinessException when the command was rejected and the policy stalls on rejections
	 */
	public static <DOMAIN_EVENT_TYPE> Outcome.ReactionResult react (
			Policy<DOMAIN_EVENT_TYPE> policy, String policyName, Event<DOMAIN_EVENT_TYPE> event,
			PolicyRejectionHandling onRejection, Instance instance, CommandExecutionCapability<DOMAIN_EVENT_TYPE> executor,
			EventSource<DOMAIN_EVENT_TYPE> domainStream ) {

		Optional<Command<DOMAIN_EVENT_TYPE>> answer = policy.react(event);
		if ( answer == null ) {
			throw new IllegalStateException("policy %s answered null for %s: answer Optional.empty() for an event it does not act on"
					.formatted(policyName, event.reference()));
		}
		if ( answer.isEmpty() ) {
			return new Outcome.Ignored();
		}
		Command<DOMAIN_EVENT_TYPE> command = answer.get();
		String commandName = command.commandName();

		if ( !domainStream.query(raisedInReactionTo(policyName, event)).isEmpty() ) {
			LOGGER.debug("policy {} reacted to {} before, not reacting again", policyName, event.reference());
			return new Outcome.AlreadyReacted(commandName);
		}

		// the reaction continues the flow of the event it reacts to, and names that event as its cause
		Tracing tracing = Tracing.actorAndChannel(policyName, CHANNEL).instance(instance)
				.causedBy(event.reference().id().value());
		String correlationId = Tracing.readFrom(event).correlationId();
		if ( correlationId != null ) {
			tracing = tracing.correlationId(correlationId);
		}

		try {
			Optional<EventReference> appended = executor.executeWithRetry(command, idempotencyKey(policyName, event), tracing, RetryPolicy.DEFAULT);
			return new Outcome.Reacted(commandName, appended);
		} catch ( IdempotencyKeyConflictException reactedBefore ) {
			// some keys stored, some not: the command decided otherwise than the first time, for an event
			// reacted to before
			LOGGER.debug("policy {} reacted to {} before, and {} decided otherwise this time; the first reaction stands",
					policyName, event.reference(), commandName);
			return new Outcome.AlreadyReacted(commandName);
		} catch ( BusinessException rejected ) {
			if ( onRejection == PolicyRejectionHandling.SKIP ) {
				LOGGER.info("policy {}: {} rejected for {} ({}) - skipping it, as registered",
						policyName, commandName, event.reference(), rejected.getMessage());
				return new Outcome.ReactionRejected(commandName, rejected.getMessage());
			}
			throw rejected;
		}
	}

}
