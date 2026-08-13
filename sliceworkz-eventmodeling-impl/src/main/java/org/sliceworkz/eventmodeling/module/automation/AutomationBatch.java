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
package org.sliceworkz.eventmodeling.module.automation;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.Limit;

/**
 * One batch of an automation's todo items, as a function: stream up to {@code batchSize} items, hand
 * each to {@link Automation#handle}, route whatever an item throws through {@link Automation#onFailure},
 * and report what happened as an {@link Outcome}.
 * <p>
 * The batch semantics live here and only here, on purpose. {@code AutomationProcessor} runs this loop in
 * production, wrapped in meters, bookmarks and lifecycle; the published {@code AutomationTest} harness
 * runs the same loop synchronously on the test thread. Extracting it is what keeps the two from
 * diverging: a rule like "{@code RETRY_ITEM} abandons the rest of the batch" or "a throwing
 * {@code onFailure} downgrades to {@code RETRY_ITEM}" holds in a user's test because it is literally the
 * same code that holds it at runtime.
 * <p>
 * The rules, spelled out (each pinned by {@code AutomationBatchTest}, and end to end by
 * {@code AutomationFailureRecoveryTest}):
 * <ul>
 * <li>Items are pulled from the todo list one at a time through an explicit iterator, and the next one
 * is only taken once the current one has been handled. That is deliberate rather than incidental:
 * handling an item may raise events that cancel or supersede the items behind it, and a todo list is
 * allowed to anticipate its own projection to withhold them (see
 * {@code TodoListReadModel.streamItems}).</li>
 * <li>{@code abandonRequested} is consulted at every item boundary, before the item is pulled — the
 * processor uses it to abandon a batch on stop, termination or a leadership demotion, so the fewer items
 * touched after losing a lease, the smaller the at-least-once overlap window.</li>
 * <li>A handler returning {@code null} or {@code Optional.empty()} is a handled item that produced no
 * event; {@link Outcome#lastProducedEvent()} is the last <em>non-empty</em> reference returned.</li>
 * <li>An item is never handed to {@code handle} twice within one batch. {@code RETRY_ITEM} abandons the
 * rest of the batch so the item is retried first next round; {@code CONTINUE_AND_RETRY_ITEM_LATER}
 * carries on with the items behind it; {@code STOP_AUTOMATION} abandons the batch and asks the caller to
 * stop.</li>
 * <li>{@code onFailure} itself is contained: a {@code null} return or a throw is treated as
 * {@code RETRY_ITEM}, so a broken failure handler cannot take the loop with it.</li>
 * </ul>
 */
public final class AutomationBatch {

	private static final Logger LOGGER = LoggerFactory.getLogger(AutomationBatch.class);

	private AutomationBatch ( ) {
	}

	/** What one batch did, and what the loop around it should do next. */
	public record Outcome ( long streamed, long handled, long failed, EventReference lastProducedEvent, boolean stopAutomation, Throwable lastFailure ) {

		/**
		 * A batch that handled something is progress, whatever else went wrong in it, and so is one where
		 * nothing failed — only a batch that failed and got nowhere should back off.
		 */
		public boolean gotNowhere ( ) {
			return handled == 0 && failed > 0;
		}
	}

	/**
	 * Validates and converts an automation's declared batch size — the one place the larger-than-zero
	 * rule lives.
	 *
	 * @throws IllegalArgumentException naming the automation when its declared batch size is not positive
	 */
	public static Limit batchSizeOf ( Automation<?,?,?> automation ) {
		int declaredBatchSize = automation.batchSize();
		if ( declaredBatchSize <= 0 ) {
			throw new IllegalArgumentException("batch size %d of automation '%s' is invalid, should be larger than 0".formatted(declaredBatchSize, automation.getClass().getSimpleName()));
		}
		return Limit.to(declaredBatchSize);
	}

	/**
	 * Handles one batch of todo items, containing whatever a single item throws.
	 *
	 * @param automation the automation whose todo list is read and whose {@code handle}/{@code onFailure}
	 *        are applied
	 * @param context the automation context handed to every item of this batch
	 * @param batchSize how many items to stream at most, normally {@link #batchSizeOf}
	 * @param automationName how to name the automation in log lines
	 * @param abandonRequested consulted before every item; {@code true} abandons the rest of the batch
	 * @param onItemFailure told about every throwable an item raised, before the automation's own
	 *        {@code onFailure} decides what it costs; may be {@code null}
	 */
	public static <TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> Outcome handleBatch (
			Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation,
			AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context,
			Limit batchSize,
			String automationName,
			BooleanSupplier abandonRequested,
			Consumer<Throwable> onItemFailure ) {

		long streamed = 0;
		long handled = 0;
		long failed = 0;
		EventReference lastProducedEvent = null;
		Throwable lastFailure = null;

		try ( Stream<TODO_ITEM_TYPE> items = automation.getTodoList().streamItems(batchSize) ) {
			Iterator<TODO_ITEM_TYPE> iterator = items.iterator();
			while ( iterator.hasNext() ) {
				// a demotion abandons the batch at the item boundary too: the items behind this one are
				// the new leader's to handle, and the fewer we touch after losing the lease, the smaller
				// the at-least-once overlap window
				if ( abandonRequested != null && abandonRequested.getAsBoolean() ) {
					LOGGER.debug("abandoning the rest of the batch of automation '{}' as its caller asked for it", automationName);
					return new Outcome(streamed, handled, failed, lastProducedEvent, false, lastFailure);
				}
				TODO_ITEM_TYPE item = iterator.next();
				streamed++;
				try {
					Optional<EventReference> produced = automation.handle(item, context);
					handled++;
					if ( produced != null && produced.isPresent() ) {
						lastProducedEvent = produced.get();
					}
				} catch ( Throwable t ) {
					failed++;
					lastFailure = t;
					if ( onItemFailure != null ) {
						onItemFailure.accept(t);
					}
					AutomationFailureAction action = determineFailureAction(automation, item, t, context, automationName);
					Throwable r = rootCauseOf(t);

					// An item is never handled twice within one batch: a failure worth retrying in milliseconds is
					// about whatever the handler called rather than about the item, and belongs inside handle().
					// What is useful here is backing the whole batch off, which is what happens between batches --
					// immediately when the todo list has moved in the meantime, after the poll interval when it has
					// not, so a conflict with another writer comes straight back and a dead dependency does not spin
					switch ( action ) {
						case CONTINUE_AND_RETRY_ITEM_LATER -> LOGGER.error("automation '{}' failed on a todo item, leaving it for a later batch and carrying on: rootcause {} : {}", automationName, r.getClass(), r.getMessage(), t);
						case RETRY_ITEM -> {
							LOGGER.error("automation '{}' failed on a todo item, abandoning the rest of this batch so it is retried first: rootcause {} : {}", automationName, r.getClass(), r.getMessage(), t);
							return new Outcome(streamed, handled, failed, lastProducedEvent, false, lastFailure);
						}
						case STOP_AUTOMATION -> {
							LOGGER.error("automation '{}' failed on a todo item: rootcause {} : {}", automationName, r.getClass(), r.getMessage(), t);
							return new Outcome(streamed, handled, failed, lastProducedEvent, true, lastFailure);
						}
					}
				}
			}
		}
		return new Outcome(streamed, handled, failed, lastProducedEvent, false, lastFailure);
	}

	private static <TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> AutomationFailureAction determineFailureAction ( Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation, TODO_ITEM_TYPE item, Throwable cause, AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> context, String automationName ) {
		try {
			AutomationFailureAction action = automation.onFailure(item, cause, context);
			return action != null ? action : AutomationFailureAction.RETRY_ITEM;
		} catch ( Throwable t ) {
			LOGGER.error("failure handler of automation '{}' threw, abandoning the rest of this batch", automationName, t);
			return AutomationFailureAction.RETRY_ITEM;
		}
	}

	private static Throwable rootCauseOf ( Throwable t ) {
		return t.getCause() == null ? t : rootCauseOf(t.getCause());
	}

}
