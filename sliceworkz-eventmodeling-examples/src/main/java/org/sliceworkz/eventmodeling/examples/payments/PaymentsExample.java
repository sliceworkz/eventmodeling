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
package org.sliceworkz.eventmodeling.examples.payments;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.features.abandonedpayments.AbandonedPaymentsReadModel;
import org.sliceworkz.eventmodeling.examples.payments.features.abandonedpayments.AbandonedPaymentsReadModel.AbandonedPayment;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.ExecutePaymentAutomation;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentsToExecuteTodoList;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.SimulatedPaymentGateway;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * Runs {@link ExecutePaymentAutomation} through every failure it is written for, and prints what
 * happened.
 * <pre>
 * mvn compile exec:java -Dexec.mainClass="org.sliceworkz.eventmodeling.examples.payments.PaymentsExample"
 * </pre>
 * The scenario, in order:
 * <ol>
 *   <li>a payment that just works</li>
 *   <li>a payment the gateway rejects outright — abandoned immediately, into the dead-letter view</li>
 *   <li>a payment declined twice and then accepted — deferred between attempts, and the payments behind
 *       it are handled meanwhile</li>
 *   <li>the gateway going down entirely — the automation keeps its work and backs off instead of
 *       hammering it, and picks everything up when it comes back</li>
 * </ol>
 */
public class PaymentsExample {

	public static void main ( String[] args ) throws Exception {

		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();

		SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();
		PaymentsToExecuteTodoList todoList = new PaymentsToExecuteTodoList();

		var builder = BoundedContext.newBuilder(Payments.class)
				.name("payments")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("payments-app"));
		builder.features().rootPackage(PaymentsExample.class.getPackage()).done();

		// The two lines the feature slice cannot write for itself: the todo list is registered as an
		// eventually consistent read model, and the automation is handed it together with the adapter
		// onto the outside world.
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new ExecutePaymentAutomation(todoList, gateway));

		Payments payments = (Payments) builder.build();
		payments.start();

		try {
			// ---------------------------------------------------------------- 1. the happy path
			gateway.declineNextAttempts("BE68539007547034", 2); // used by step 3, set up front

			request(payments, "p-1", "BE00000000000001", 12_500);
			await(() -> todoList.outstandingCount() == 0, "the first payment to go through");
			System.out.println("1. executed, todo list is empty again");

			// ---------------------------------------------------------------- 2. rejected for good
			request(payments, "p-2", "XX99999999999999", 5_000);
			await(() -> !abandoned(payments).isEmpty(), "the rejected payment to be abandoned");
			System.out.println("2. abandoned: " + abandoned(payments));

			// ---------------------------------------------------------------- 3. declined, then accepted
			// p-3 is declined twice; p-4 is requested behind it and must not wait for it, because the
			// automation answers a decline with CONTINUE_AND_RETRY_ITEM_LATER
			request(payments, "p-3", "BE68539007547034", 80_000);
			request(payments, "p-4", "BE00000000000002", 1_000);
			await(() -> todoList.outstandingCount() == 1, "the payment behind the declined one to be handled");
			System.out.println("3. p-4 went through while p-3 waits for its next attempt");

			await(() -> todoList.outstandingCount() == 0, "the declined payment to be accepted on a later attempt");
			System.out.println("3. p-3 executed after its retries");

			// ---------------------------------------------------------------- 4. the gateway falls over
			gateway.available(false);
			int callsBeforeOutage = gateway.calls();
			request(payments, "p-5", "BE00000000000003", 42_000);

			Thread.sleep(6_000);
			System.out.println("4. gateway down: " + (gateway.calls() - callsBeforeOutage)
					+ " attempts in 6s (backing off, not hammering), still outstanding: " + todoList.outstandingCount());

			AutomationStatus status = payments.automations().get(0);
			System.out.println("4. status: running=" + status.running()
					+ " consecutiveFailedBatches=" + status.consecutiveFailedBatches()
					+ " lastFailure=" + (status.lastFailure() == null ? "none" : status.lastFailure().message()));

			gateway.available(true);
			await(() -> todoList.outstandingCount() == 0, "the automation to catch up once the gateway is back");
			System.out.println("4. gateway back: everything caught up without anyone restarting anything");

		} finally {
			payments.terminate();
			eventStorage.close();
		}
	}

	/** {@code read} infers its type from the assignment, so the result gets a name rather than a chain. */
	private static List<AbandonedPayment> abandoned ( Payments payments ) {
		AbandonedPaymentsReadModel deadLetters = payments.read(AbandonedPaymentsReadModel.class);
		return deadLetters.abandoned();
	}

	/** Requesting a payment. A command would be the usual way in; this keeps the example on the automation. */
	private static void request ( Payments payments, String id, String iban, long amountInCents ) {
		payments.event(new PaymentRequested(DomainConceptId.of(id), iban, amountInCents));
	}

	private static void await ( BooleanSupplier condition, String what ) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
		while ( !condition.getAsBoolean() ) {
			if ( System.currentTimeMillis() > deadline ) {
				throw new IllegalStateException("timed out waiting for " + what);
			}
			Thread.sleep(100);
		}
	}

}
