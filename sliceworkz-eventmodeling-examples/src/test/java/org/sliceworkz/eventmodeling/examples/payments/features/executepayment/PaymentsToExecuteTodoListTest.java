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

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsOutboundEvent;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentsToExecuteTodoList.PaymentToExecute;
import org.sliceworkz.eventmodeling.testing.AutomationTest;

/**
 * The todo list on its own: which events put a payment on the queue, which take it off, and which
 * merely defer it. {@code given(...).expectTodoItems(...)} is the whole test — no batch ever runs, so
 * these are pure projection tests, the counterpart of what {@code ExecutePaymentAutomationTest}
 * asserts about the handling.
 */
public class PaymentsToExecuteTodoListTest extends AutomationTest<PaymentToExecute, PaymentsDomainEvent, PaymentsInboundEvent, PaymentsOutboundEvent> {

	private static final PaymentId PAYMENT_1 = PaymentsDomain.PAYMENT.id("p1");
	private static final PaymentId PAYMENT_2 = PaymentsDomain.PAYMENT.id("p2");
	private static final String IBAN_1 = "BE68539007547034";
	private static final String IBAN_2 = "BE71096123456769";

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
		return new ExecutePaymentAutomation(todoList, new SimulatedPaymentGateway());
	}

	@Test
	void paymentsAreOfferedInTheOrderTheyWereRequested ( ) {
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentRequested(PAYMENT_2, IBAN_2, 50_00))
			.expectTodoItems(
				new PaymentToExecute(PAYMENT_1, IBAN_1, 100_00, 0, null),
				new PaymentToExecute(PAYMENT_2, IBAN_2, 50_00, 0, null));
	}

	@Test
	void anExecutedPaymentLeavesTheQueue ( ) {
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentRequested(PAYMENT_2, IBAN_2, 50_00),
				new PaymentExecuted(PAYMENT_1, "GW-1"))
			.expectTodoItems(new PaymentToExecute(PAYMENT_2, IBAN_2, 50_00, 0, null));
	}

	@Test
	void anAbandonedPaymentLeavesTheQueueForGood ( ) {
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentAbandoned(PAYMENT_1, "rejected", 1))
			.expectNoTodoItems();
	}

	@Test
	void aFailedAttemptDefersThePaymentUntilItsDueTime ( ) {
		Instant notYetDue = Instant.now().plusSeconds(3600);
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentAttemptFailed(PAYMENT_1, "declined", 1, notYetDue))
			// withheld, not dropped: streamItems offers nothing while the item stays outstanding
			.expectNoTodoItems();
		assertEquals(1, todoList.outstandingCount(), "the deferred payment is still outstanding");
	}

	@Test
	void aFailedAttemptWhoseDueTimeHasPassedIsOfferedAgainCarryingItsAttemptCount ( ) {
		Instant alreadyDue = Instant.now().minusSeconds(1);
		given(new PaymentRequested(PAYMENT_1, IBAN_1, 100_00),
				new PaymentAttemptFailed(PAYMENT_1, "declined", 1, alreadyDue))
			.expectTodoItems(new PaymentToExecute(PAYMENT_1, IBAN_1, 100_00, 1, alreadyDue));
	}

}
