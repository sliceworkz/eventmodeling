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
package org.sliceworkz.eventmodeling.examples.payments.features.executepayment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentGateway.PaymentDeclinedException;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentGateway.PaymentGatewayUnavailableException;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentGateway.PaymentRejectedException;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentsToExecuteTodoList.PaymentToExecute;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Sends outstanding payments to an external gateway — a worked example of an automation whose work can
 * fail, and of what to do about it.
 * <p>
 * <strong>The rule that shapes everything else:</strong> the todo list is the queue, and it is projected
 * from events. So an automation cannot "remember" that it gave up on something, cannot hold a retry
 * counter in a field, and cannot put an item aside — anything not recorded as an event is undone by the
 * next restart, and the item comes straight back. Every decision below therefore ends in either an event
 * or an {@link AutomationFailureAction}, and usually both.
 *
 * <h2>What each failure costs</h2>
 * <table border="1">
 *   <caption>the policy in {@link #onFailure}</caption>
 *   <tr><th>failure</th><th>event recorded</th><th>action</th><th>why</th></tr>
 *   <tr>
 *     <td>gateway unreachable</td><td>none</td><td>{@code RETRY_ITEM}</td>
 *     <td>Not a fact about this payment — every payment behind it would fail the same way, so there is
 *         nothing to record and nothing to gain by moving on. Holding the order costs nothing here and
 *         the framework backs the whole automation off (see {@link #delayBeforeNextBatch}).</td>
 *   </tr>
 *   <tr>
 *     <td>declined, may work later</td><td>{@code PaymentAttemptFailed}</td><td>{@code CONTINUE_AND_RETRY_ITEM_LATER}</td>
 *     <td>A fact about this one payment. Recording it defers the item — the todo list withholds it until
 *         its due time — and the other payments are unaffected, so the batch carries on.</td>
 *   </tr>
 *   <tr>
 *     <td>rejected, or out of attempts</td><td>{@code PaymentAbandoned}</td><td>{@code CONTINUE_AND_RETRY_ITEM_LATER}</td>
 *     <td>The dead letter. The item leaves the todo list for good because the projection drops it, and
 *         {@code AbandonedPaymentsReadModel} over those same events is where an operator finds it.</td>
 *   </tr>
 *   <tr>
 *     <td>anything unexpected</td><td>none</td><td>{@code STOP_AUTOMATION}</td>
 *     <td>A bug or a misconfiguration: nobody knows whether continuing is safe, so a human decides.
 *         Raises {@code AutomationStopped} and shows up in {@code AutomationAdminCapability}.</td>
 *   </tr>
 * </table>
 *
 * <h2>The two delays, which are not the same thing</h2>
 * <ul>
 *   <li><strong>Per item</strong> — {@code PaymentAttemptFailed.nextAttemptDueAt}, honoured by the todo
 *       list. Defers <em>one</em> payment while everything else proceeds. Durable, because it is an
 *       event.</li>
 *   <li><strong>Per automation</strong> — {@link #delayBeforeNextBatch}. Paces the <em>whole</em>
 *       automation when batches keep failing, which is what you want when the gateway is down and every
 *       item would fail. Not durable, and does not need to be: a restart is a perfectly good reason to
 *       try again immediately.</li>
 * </ul>
 */
public class ExecutePaymentAutomation implements Automation<PaymentToExecute,PaymentsDomainEvent,PaymentsOutboundEvent> {

	/** After this many recorded failures a payment is abandoned rather than deferred again. */
	static final int MAX_ATTEMPTS = 3;

	private final PaymentsToExecuteTodoList todoList;
	private final PaymentGateway gateway;

	public ExecutePaymentAutomation ( PaymentsToExecuteTodoList todoList, PaymentGateway gateway ) {
		this.todoList = todoList;
		this.gateway = gateway;
	}

	@Override
	public TodoListReadModel<PaymentsDomainEvent,PaymentToExecute> getTodoList ( ) {
		return todoList;
	}

	@Override
	public Optional<EventReference> handle ( PaymentToExecute payment, AutomationContext<PaymentsDomainEvent,PaymentsOutboundEvent> context ) {
		// Delivery is at least once: this method runs again for the same payment whenever the events it
		// raised did not reach the todo list -- after a crash between the append and the bookmark, and
		// after every failure below. So the key is derived from the payment, never from the attempt: a
		// key that changed per attempt would de-duplicate nothing. The gateway is handed the same key,
		// for the same reason -- appending our event once is no use if the money moved twice.
		String idempotencyKey = "payment-executed:" + payment.paymentId().value();

		String gatewayReference = gateway.execute(payment.iban(), payment.amountInCents(), idempotencyKey);

		return context.event(new PaymentExecuted(payment.paymentId(), gatewayReference), idempotencyKey);
	}

	@Override
	public AutomationFailureAction onFailure ( PaymentToExecute payment, Throwable cause, AutomationContext<PaymentsDomainEvent,PaymentsOutboundEvent> context ) {
		return switch ( cause ) {

			// The gateway, not the payment. Nothing is recorded: an outage is not a fact about this
			// payment, and writing one event per item per attempt would fill the stream with the shape
			// of the incident rather than of the business.
			case PaymentGatewayUnavailableException unavailable -> AutomationFailureAction.RETRY_ITEM;

			// This payment, permanently. Straight to the dead letter.
			case PaymentRejectedException rejected -> abandon(payment, rejected.getMessage(), context);

			// This payment, for now. Defer it -- unless it has used up its attempts, in which case this
			// is where "retry a few times then give up" is actually decided. Note that the count comes
			// off the todo item, which projected it from the events: nothing here counts in memory.
			case PaymentDeclinedException declined -> {
				int attempt = payment.attempts() + 1;
				if ( attempt >= MAX_ATTEMPTS ) {
					yield abandon(payment, "declined %d times, last reason: %s".formatted(attempt, declined.getMessage()), context);
				}
				context.event(
					new PaymentAttemptFailed(payment.paymentId(), declined.getMessage(), attempt, Instant.now().plus(retryDelayFor(attempt))),
					"payment-attempt-failed:%s:%d".formatted(payment.paymentId().value(), attempt));
				yield AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER;
			}

			// Nobody planned for this one, so nobody knows whether it is safe to carry on. An operator
			// sees it through AutomationAdminCapability.automations() and restarts once it is understood.
			default -> AutomationFailureAction.STOP_AUTOMATION;
		};
	}

	/**
	 * How long the automation waits before its next batch. The default doubles from 10 seconds to 5
	 * minutes; payments are worth chasing harder than that, so this starts at 2 seconds and gives up
	 * growing at a minute.
	 * <p>
	 * Only consulted when the processor decided to wait at all. The case that matters is a batch that
	 * failed and handled nothing — the gateway being down — where the automation is held here even if
	 * new payments keep arriving, because more work appearing says nothing about the gateway being back.
	 */
	@Override
	public Duration delayBeforeNextBatch ( int consecutiveFailedBatches, Throwable lastFailure ) {
		if ( consecutiveFailedBatches <= 0 ) {
			return Duration.ofSeconds(2); // idle: how quickly a newly requested payment is picked up
		}
		Duration backoff = Duration.ofSeconds(2).multipliedBy(1L << Math.min(consecutiveFailedBatches - 1, 5));
		return backoff.compareTo(Duration.ofMinutes(1)) > 0 ? Duration.ofMinutes(1) : backoff;
	}

	/**
	 * Payments are independent of one another, so there is nothing to gain from a small batch: no payment
	 * here can cancel or supersede the ones behind it. An automation where handling one item invalidates
	 * the next returns {@code 1} instead, and pays a projection round trip per item for it.
	 */
	@Override
	public int batchSize ( ) {
		return DEFAULT_BATCH_SIZE;
	}

	private AutomationFailureAction abandon ( PaymentToExecute payment, String reason, AutomationContext<PaymentsDomainEvent,PaymentsOutboundEvent> context ) {
		context.event(
			new PaymentAbandoned(payment.paymentId(), reason, payment.attempts() + 1),
			"payment-abandoned:" + payment.paymentId().value());
		// the item is gone once this event is projected, so the rest of the batch may proceed
		return AutomationFailureAction.CONTINUE_AND_RETRY_ITEM_LATER;
	}

	private static Duration retryDelayFor ( int attempt ) {
		return Duration.ofSeconds(5L << (attempt - 1)); // 5s, 10s, 20s, ...
	}

}
