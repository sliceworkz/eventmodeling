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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentsToExecuteTodoList.PaymentToExecute;
import org.sliceworkz.eventmodeling.testing.AutomationTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * The worked example of testing an automation with {@link AutomationTest}: every branch of
 * {@link ExecutePaymentAutomation#onFailure}, driven deterministically — the same four paths
 * {@code PaymentsExample} demonstrates with a running context and wall-clock waits take no polling
 * and no sleeps here, because the harness runs the production batch loop synchronously on the test
 * thread.
 * <p>
 * Three patterns worth copying:
 * <ul>
 * <li><b>The fake dependency is a field, the automation is built on it.</b> {@link #automation()} is
 * called once per test; the test rigs {@link SimulatedPaymentGateway} before or between rounds.</li>
 * <li><b>History is seeded, not waited for.</b> The three-declines scenario does not sit out two
 * retry delays — it seeds the {@code PaymentAttemptFailed} events a past run would have recorded,
 * with a due time already reached, and runs one batch on top.</li>
 * <li><b>Redelivery is asserted, not hoped about.</b> {@code whenItemsAreRedelivered()} re-offers an
 * item whose events never reached the todo list — the crash-between-append-and-bookmark case — and
 * the test proves the item-derived idempotency key stops both the duplicate event <em>and</em> the
 * duplicate charge.</li>
 * </ul>
 */
public class ExecutePaymentAutomationTest extends AutomationTest<PaymentToExecute, PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

	private static final DomainConceptId PAYMENT_1 = DomainConceptId.of("p1");
	private static final DomainConceptId PAYMENT_2 = DomainConceptId.of("p2");
	private static final String IBAN_1 = "BE68539007547034";
	private static final String IBAN_2 = "BE71096123456769";

	private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();
	private PaymentsToExecuteTodoList todoList;

	@Override
	public Class<PaymentsDomainEvent> domainEventType ( ) {
		return PaymentsDomainEvent.class;
	}

	@Override
	public Class<PaymentsInboundEvent> inboundEventType ( ) {
		return PaymentsInboundEvent.class;
	}

	@Override
	public Class<PaymentsOutboundEvent> outboundEventType ( ) {
		return PaymentsOutboundEvent.class;
	}

	@Override
	public Automation<PaymentToExecute, PaymentsDomainEvent, PaymentsOutboundEvent> automation ( ) {
		todoList = new PaymentsToExecuteTodoList();
		return new ExecutePaymentAutomation(todoList, gateway);
	}

	/** The reference the simulated gateway mints for a payment's idempotency key — deterministic. */
	private static String gatewayReferenceFor ( DomainConceptId paymentId ) {
		return "GW-" + Integer.toHexString(("payment-executed:" + paymentId.value()).hashCode());
	}

	@Test
	void aPaymentIsExecutedAndLeavesTheTodoList ( ) {
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00))
			.expectTodoItems(new PaymentToExecute(PAYMENT_1, IBAN_1, 100_00, 0, null))
			.whenBatchRuns()
			.itemsHandled(1)
			.events(new PaymentExecuted(PAYMENT_1, gatewayReferenceFor(PAYMENT_1)))
			.and()
			// the PaymentExecuted event is projected at the start of the next round and drops the item
			.expectNoTodoItems();
	}

	@Test
	void aRedeliveredPaymentIsChargedOnceAndRecordedOnce ( ) {
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00))
			.whenBatchRuns()
			.events(new PaymentExecuted(PAYMENT_1, gatewayReferenceFor(PAYMENT_1)))
			.and()
			// the todo list is deliberately not caught up: the PaymentExecuted event has not reached
			// it, so the same payment is offered again -- what a crash between the append and the
			// bookmark does in production
			.whenItemsAreRedelivered()
			.itemsHandled(1)
			.noEvents(); // the item-derived key made the duplicate append a no-op

		// and the gateway was called twice but moved the money once: it de-duplicates on the same
		// item-derived key the event carries, which is why handle() hands it the key at all
		assertEquals(2, gateway.calls(), "the retry did reach the gateway");
	}

	@Test
	void aRejectedPaymentIsAbandonedAndTheBatchCarriesOn ( ) {
		given(new PaymentRequested(PAYMENT_1, "NL91ABNA0417164300", 100_00),  // not a BE IBAN: rejected
				new PaymentRequested(PAYMENT_2, IBAN_2, 50_00))
			.whenBatchRuns()
			.itemsFailed(1)
			.itemsHandled(1)
			.automationStillRunning()
			.events(
				// the dead letter, recorded from onFailure before the batch moved on ...
				new PaymentAbandoned(PAYMENT_1, "unsupported IBAN NL91ABNA0417164300", 1),
				// ... and the payment behind it, executed in the same batch
				new PaymentExecuted(PAYMENT_2, gatewayReferenceFor(PAYMENT_2)))
			.and()
			.expectNoTodoItems();
	}

	@Test
	void aDeclinedPaymentIsDeferredWhileThePaymentsBehindItProceed ( ) {
		gateway.declineNextAttempts(IBAN_1, 1);

		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentRequested(PAYMENT_2, IBAN_2, 50_00))
			.whenBatchRuns()
			.itemsFailed(1)
			.itemsHandled(1)
			.and()
			// the declined payment is deferred, not dropped: still outstanding, withheld until due
			.expectNoTodoItems();

		assertEquals(1, todoList.outstandingCount(), "the deferred payment is still outstanding, just not due");

		// the recorded attempt carries the count and the due time the todo list projects
		PaymentAttemptFailed attempt = (PaymentAttemptFailed) domainEvents().stream()
				.filter(e -> e instanceof PaymentAttemptFailed).findFirst().orElseThrow();
		assertEquals(PAYMENT_1, attempt.paymentId());
		assertEquals(1, attempt.attempt());
		assertTrue(attempt.nextAttemptDueAt().isAfter(Instant.now()), "the next attempt is due in the future");
	}

	@Test
	void aPaymentOutOfAttemptsIsAbandonedInsteadOfDeferredAgain ( ) {
		gateway.declineNextAttempts(IBAN_1, 1);

		// seed the history a past run would have recorded: two failed attempts, the retry delay
		// already elapsed -- no test ever waits out a due time
		Instant alreadyDue = Instant.now().minusSeconds(1);
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentAttemptFailed(PAYMENT_1, "daily limit reached for " + IBAN_1, 2, alreadyDue))
			.expectTodoItems(new PaymentToExecute(PAYMENT_1, IBAN_1, 100_00, 2, alreadyDue))
			.whenBatchRuns()
			.itemsFailed(1)
			.events(new PaymentAbandoned(PAYMENT_1, "declined 3 times, last reason: daily limit reached for " + IBAN_1, 3))
			.and()
			.expectNoTodoItems();
	}

	@Test
	void aGatewayOutageRetriesTheItemWithoutRecordingAnything ( ) {
		var definition = given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00));
		gateway.available(false);

		definition.whenBatchRuns()
			.itemsFailed(1)
			.itemsHandled(0)
			.noEvents()               // an outage is not a fact about this payment
			.automationStillRunning();

		// the gateway comes back, and the very next batch catches up by itself
		gateway.available(true);
		definition.whenBatchRuns()
			.itemsHandled(1)
			.events(new PaymentExecuted(PAYMENT_1, gatewayReferenceFor(PAYMENT_1)));
	}

	@Test
	void anUnexpectedFailureStopsTheAutomationForAHumanToLookAt ( ) {
		// a malformed request nobody planned for: the gateway dies on the null IBAN
		var definition = given(new PaymentRequested(PAYMENT_1, null, 100_00));

		definition.whenBatchRuns().automationStopped();

		// restarting without fixing the cause finds the same item at the head and stops again --
		// which is exactly what AutomationAdminCapability.restartAutomation does in production
		definition.restartAutomation().whenBatchRuns().automationStopped();
	}

	private List<PaymentsDomainEvent> domainEvents ( ) {
		return domainStream().query(EventQuery.matchAll()).map(Event::data).toList();
	}

	/** Guards the deterministic gateway reference the assertions above rely on. */
	@Test
	void theSimulatedGatewayMintsDeterministicReferences ( ) {
		assertEquals(gatewayReferenceFor(PAYMENT_1), gateway.execute(IBAN_1, 1, "payment-executed:p1"));
	}

}
