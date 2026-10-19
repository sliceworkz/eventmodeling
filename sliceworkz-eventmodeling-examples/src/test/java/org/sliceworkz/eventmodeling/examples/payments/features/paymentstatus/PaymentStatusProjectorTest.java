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
package org.sliceworkz.eventmodeling.examples.payments.features.paymentstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.examples.payments.features.paymentstatus.PaymentStatus.Status;
import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelQuery;
import org.sliceworkz.eventmodeling.testing.sql.SqlReadModelTest;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * The worked example of testing an SQL read model with {@link SqlReadModelTest}: the same tests run
 * against H2, which the example application uses, and PostgreSQL, which a deployment would — the SQL a
 * read model writes is the part most likely to behave differently between the two.
 */
public class PaymentStatusProjectorTest extends SqlReadModelTest<PaymentsDomainEvent> {

	private static final PaymentId PAYMENT_1 = PaymentsDomain.PAYMENT.id("p1");
	private static final PaymentId PAYMENT_2 = PaymentsDomain.PAYMENT.id("p2");
	private static final String IBAN = "BE68539007547034";

	abstract static class Tests extends AbstractSqlReadModelTests<PaymentsDomainEvent> {

		@Override
		protected SqlReadModelProjector<PaymentsDomainEvent> createProjector ( DataSource dataSource ) {
			return new PaymentStatusProjector(dataSource);
		}

		@Override
		protected SqlReadModelQuery createQuery ( DataSource dataSource ) {
			return new PaymentStatusQuery(dataSource);
		}

		@Override
		protected String[] tablesToDrop ( ) {
			return new String[] { "rm_payment_status_payments", "rm_payment_status_projection_bookmark" };
		}

		private PaymentStatusQuery statuses ( ) {
			return query();
		}

		@Test
		void aRequestedPaymentIsListedAsRequested ( ) {
			projectEvents(new PaymentRequested(PAYMENT_1, IBAN, 100_00));

			assertEquals(new PaymentStatus("p1", IBAN, 100_00, Status.REQUESTED, 0, null),
					statuses().status(PAYMENT_1).orElseThrow().data());
		}

		@Test
		void aPaymentMovesThroughItsStatuses ( ) {
			projectEvents(
					new PaymentRequested(PAYMENT_1, IBAN, 100_00),
					new PaymentAttemptFailed(PAYMENT_1, "daily limit reached", 1, Instant.now()));
			assertEquals(new PaymentStatus("p1", IBAN, 100_00, Status.DEFERRED, 1, "daily limit reached"),
					statuses().status(PAYMENT_1).orElseThrow().data());

			projectEvents(new PaymentExecuted(PAYMENT_1, "GW-1"));
			assertEquals(new PaymentStatus("p1", IBAN, 100_00, Status.EXECUTED, 1, "GW-1"),
					statuses().status(PAYMENT_1).orElseThrow().data());
		}

		@Test
		void theAnswerSaysWhichEventItReflects ( ) {
			projectEvents(new PaymentRequested(PAYMENT_1, IBAN, 100_00));
			EventReference executed = projectEvents(new PaymentExecuted(PAYMENT_1, "GW-1")).orElseThrow();

			ReadModelResult<PaymentStatus> answer = statuses().status(PAYMENT_1).orElseThrow();
			assertTrue(!executed.happenedAfter(answer.upTo()), "a caller holding the reference of its own write can tell the answer includes it");
		}

		@Test
		void aPaymentRequestedAgainIsNotListedTwice ( ) {
			projectEvents(new PaymentRequested(PAYMENT_1, IBAN, 100_00));
			projectEvents(new PaymentRequested(PAYMENT_1, IBAN, 100_00));

			assertEquals(1, statuses().withStatus(Status.REQUESTED).size());
		}

		@Test
		void paymentsAreFoundByStatus ( ) {
			projectEvents(
					new PaymentRequested(PAYMENT_1, IBAN, 100_00),
					new PaymentRequested(PAYMENT_2, "XX99999999999999", 50_00),
					new PaymentAbandoned(PAYMENT_2, "unsupported IBAN XX99999999999999", 1));

			List<PaymentStatus> abandoned = statuses().withStatus(Status.ABANDONED);
			assertEquals(List.of(new PaymentStatus("p2", "XX99999999999999", 50_00, Status.ABANDONED, 0, "unsupported IBAN XX99999999999999")), abandoned);
		}

		@Test
		void aRestartResumesAfterTheLastCommittedBatch ( ) {
			Optional<EventReference> last = projectEvents(new PaymentRequested(PAYMENT_1, IBAN, 100_00), new PaymentExecuted(PAYMENT_1, "GW-1"));

			// the position was committed with the rows, so a restarted projector resumes from it
			assertEquals(last, restartedProjector().resumeFrom());
		}

	}

	@Nested
	class OnH2 extends Tests {
		@Override
		protected DataSource createDataSource ( ) {
			return h2DataSource();
		}
	}

	@Nested
	class OnPostgres extends Tests {
		@Override
		protected DataSource createDataSource ( ) {
			return postgresDataSource();
		}
	}

}
