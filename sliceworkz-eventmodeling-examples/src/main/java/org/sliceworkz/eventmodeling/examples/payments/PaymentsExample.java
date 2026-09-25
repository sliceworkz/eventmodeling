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

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

import org.sliceworkz.eventmodeling.automation.AutomationStatus;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsInboundEvent.PaymentInstructionReceived;
import org.sliceworkz.eventmodeling.examples.payments.features.abandonedpayments.AbandonedPaymentsReadModel;
import org.sliceworkz.eventmodeling.examples.payments.features.abandonedpayments.AbandonedPaymentsReadModel.AbandonedPayment;
import org.sliceworkz.eventmodeling.examples.payments.features.announcepayment.InMemoryPaymentNotifications;
import org.sliceworkz.eventmodeling.examples.payments.features.announcepayment.PaymentNotifications;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.ExecutePaymentAutomation;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.PaymentGateway;
import org.sliceworkz.eventmodeling.examples.payments.features.executepayment.SimulatedPaymentGateway;
import org.sliceworkz.eventmodeling.examples.payments.features.paymentstatus.PaymentStatus;
import org.sliceworkz.eventmodeling.examples.payments.features.paymentstatus.PaymentStatusFeatureSlice;
import org.sliceworkz.eventmodeling.examples.payments.features.paymentstatus.PaymentStatusQuery;
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
 * Payments come in as {@link PaymentInstructionReceived} through {@code incoming(...)}, the way an
 * external system's message would, and a translator turns each into {@code PaymentRequested}. Each
 * payment that goes through is announced on the outbound stream, and a dispatcher delivers the
 * announcement to {@link PaymentNotifications}.
 * <p>
 * What reaches outside the context is the application's to construct and bind to a port: the gateway,
 * the notifications and the database the SQL read model lives in. The feature slices ask for them by
 * port. The scenario watches the same gateway to see the money move, the notifications to see what was
 * announced, the live {@link AbandonedPaymentsReadModel} to see a payment given up on, and
 * {@link PaymentStatusQuery} for every payment's status out of SQL.
 */
public class PaymentsExample {

	public static void main ( String[] args ) throws Exception {

		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();

		// The adapter onto the outside world is the application's to choose; the feature slice asks
		// for it through the port, and wires its own todo list and automation around it.
		SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();
		InMemoryPaymentNotifications notifications = new InMemoryPaymentNotifications();

		// The SQL read model's database. An in-memory H2 makes the read model EPHEMERAL: projected in
		// full before start() returns, and rebuilt from the events on every start. A PostgreSQL
		// DataSource here would make it SHARED, projected by one elected instance for all of them.
		JdbcDataSource readModels = new JdbcDataSource();
		readModels.setURL("jdbc:h2:mem:payments-readmodels;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
		PaymentStatusQuery statuses = new PaymentStatusQuery(readModels);

		Payments payments = BoundedContext.newBuilder(Payments.class)
				.name("payments")
				.eventStorage(eventStorage)
				.instance(InstanceFactory.determine("payments-app"))
				.adapter(gateway).forPort(PaymentGateway.class)
				.adapter(notifications).forPort(PaymentNotifications.class)
				.adapter(readModels).forPort(DataSource.class, PaymentStatusFeatureSlice.READ_MODELS)
				.features().rootPackage(PaymentsExample.class.getPackage()).done()
				.build();
		payments.start();

		try {
			// ---------------------------------------------------------------- 1. the happy path
			gateway.declineNextAttempts("BE68539007547034", 2); // used by step 3, set up front

			request(payments, "p-1", "BE00000000000001", 12_500);
			await(() -> gateway.executed("BE00000000000001"), "the first payment to go through");
			System.out.println("1. executed");
			await(() -> !notifications.published().isEmpty(), "the first payment to be announced downstream");
			System.out.println("1. announced downstream: " + notifications.published());
			await(() -> hasStatus(statuses, "p-1", PaymentStatus.Status.EXECUTED), "the SQL read model to show the first payment executed");
			System.out.println("1. SQL read model: " + statuses.status(PaymentsDomain.PAYMENT.id("p-1")).orElseThrow().data());

			// ---------------------------------------------------------------- 2. rejected for good
			request(payments, "p-2", "XX99999999999999", 5_000);
			await(() -> !abandoned(payments).isEmpty(), "the rejected payment to be abandoned");
			System.out.println("2. abandoned: " + abandoned(payments));

			// ---------------------------------------------------------------- 3. declined, then accepted
			// p-3 is declined twice; p-4 is requested behind it and must not wait for it, because the
			// automation answers a decline with CONTINUE_AND_RETRY_ITEM_LATER
			request(payments, "p-3", "BE68539007547034", 80_000);
			request(payments, "p-4", "BE00000000000002", 1_000);
			await(() -> gateway.executed("BE00000000000002"), "the payment behind the declined one to be handled");
			System.out.println("3. p-4 went through while p-3 " + (gateway.executed("BE68539007547034") ? "was already accepted" : "waits for its next attempt"));

			await(() -> gateway.executed("BE68539007547034"), "the declined payment to be accepted on a later attempt");
			System.out.println("3. p-3 executed after its retries");
			await(() -> hasStatus(statuses, "p-3", PaymentStatus.Status.EXECUTED), "the SQL read model to show p-3 executed");
			System.out.println("3. SQL read model: " + statuses.status(PaymentsDomain.PAYMENT.id("p-3")).orElseThrow().data());

			// ---------------------------------------------------------------- 4. the gateway falls over
			gateway.available(false);
			int callsBeforeOutage = gateway.calls();
			request(payments, "p-5", "BE00000000000003", 42_000);

			Thread.sleep(6_000);
			System.out.println("4. gateway down: " + (gateway.calls() - callsBeforeOutage)
					+ " attempts in 6s (backing off, not hammering), p-5 executed: " + gateway.executed("BE00000000000003"));

			AutomationStatus status = payments.automations().get(0);
			System.out.println("4. status: running=" + status.running()
					+ " consecutiveFailedBatches=" + status.consecutiveFailedBatches()
					+ " lastFailure=" + (status.lastFailure() == null ? "none" : status.lastFailure().message()));

			gateway.available(true);
			await(() -> gateway.executed("BE00000000000003"), "the automation to catch up once the gateway is back");
			System.out.println("4. gateway back: everything caught up without anyone restarting anything");

			await(() -> notifications.published().size() == 4, "every executed payment to be announced");
			System.out.println("announced downstream, once each: " + notifications.published().size()
					+ " payments; abandoned according to the SQL read model: " + statuses.withStatus(PaymentStatus.Status.ABANDONED));

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

	/**
	 * A payment instruction arriving from outside. {@code incoming} stores it on the inbound stream and
	 * returns; {@code PaymentInstructionTranslator} turns it into {@code PaymentRequested} in the
	 * background. The key is derived from the instruction, so one delivered twice is stored once.
	 */
	private static void request ( Payments payments, String id, String iban, long amountInCents ) {
		payments.incoming(new PaymentInstructionReceived(PaymentsDomain.PAYMENT.id(id), iban, amountInCents), "payment-instruction:" + id);
	}

	private static boolean hasStatus ( PaymentStatusQuery statuses, String paymentId, PaymentStatus.Status status ) {
		return statuses.status(PaymentsDomain.PAYMENT.id(paymentId))
				.map(result -> result.data().status() == status)
				.orElse(false);
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
