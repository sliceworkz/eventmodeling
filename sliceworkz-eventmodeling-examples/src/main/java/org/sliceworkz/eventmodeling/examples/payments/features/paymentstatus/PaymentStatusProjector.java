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

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelProjector;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * The write side of an SQL read model: every payment's current status, one row each, in a database of
 * the application's choosing. {@link PaymentStatusQuery} is the read side, over the same tables.
 *
 * <h2>When a read model belongs in SQL</h2>
 * When it has to outlive the process, be shared by every instance of a deployment, or be queried in
 * ways a map in memory answers badly. Point the {@code DataSource} at PostgreSQL and this read model is
 * {@code SHARED}: one elected instance projects it and every instance reads it. On an in-memory H2
 * database, as {@code PaymentsExample} runs it, it is {@code EPHEMERAL} — projected completely before
 * {@code start()} returns, and rebuilt from the events on every start. See
 * {@code CHOOSING-A-READ-MODEL.md} for the rungs below this one, which are cheaper when they suffice.
 *
 * <h2>Its position is kept next to its rows</h2>
 * Every batch commits its rows and the reference of its last event in one transaction, in a bookmark
 * table the base class creates beside {@link #createTables()}. A restart resumes from there, so the
 * rows and the position cannot disagree; dropping the tables rebuilds the read model from the start.
 *
 * <h2>Every write is safe to replay</h2>
 * The bookmark covers the ordinary restart, but a failover is at least once, so {@link #project} still
 * writes only in ways a repeated event cannot corrupt:
 * <ul>
 *   <li>{@code insertIfAbsent} creates the row on {@code PaymentRequested} and does nothing the
 *       second time. It leaves the event columns at zero, so the {@code updateOnce} right after it
 *       still applies.</li>
 *   <li>{@code updateOnce} changes a row only for an event newer than the last one that row
 *       reflects. That is what stops a replayed {@code PaymentAttemptFailed} from setting a payment
 *       that has since been executed back to {@code DEFERRED}, and it compares the whole
 *       {@code (tx, position, index)} order rather than the position alone.</li>
 * </ul>
 */
public class PaymentStatusProjector extends SqlReadModelProjector<PaymentsDomainEvent> {

	/** Shared with {@link PaymentStatusQuery}, which reads the same tables. */
	static final String TABLE_PREFIX = "rm_payment_status";
	static final String PAYMENTS = "payments";

	public PaymentStatusProjector ( DataSource dataSource ) {
		super(dataSource, TABLE_PREFIX);
	}

	@Override
	protected String[] createTables ( ) {
		return new String[] {
			"""
			CREATE TABLE IF NOT EXISTS %s (
				payment_id VARCHAR(255) PRIMARY KEY,
				iban VARCHAR(34),
				amount_in_cents BIGINT NOT NULL,
				status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
				failed_attempts INT NOT NULL DEFAULT 0,
				detail VARCHAR(1000),
				%s
			)""".formatted(table(PAYMENTS), EVENT_REF_COLUMNS)
		};
	}

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.matchAll();
	}

	@Override
	protected void project ( Event<PaymentsDomainEvent> event ) {
		switch ( event.data() ) {

			case PaymentRequested requested -> {
				insertIfAbsent(table(PAYMENTS), "payment_id = ?", new Object[] { requested.paymentId().value() },
						"payment_id, iban, amount_in_cents", requested.paymentId().value(), requested.iban(), requested.amountInCents());
				setStatus(requested.paymentId().value(), PaymentStatus.Status.REQUESTED, null);
			}

			case PaymentExecuted executed ->
				setStatus(executed.paymentId().value(), PaymentStatus.Status.EXECUTED, executed.gatewayReference());

			case PaymentAttemptFailed failed ->
				updateOnce(table(PAYMENTS),
						"status = ?, failed_attempts = ?, detail = ?",
						new Object[] { PaymentStatus.Status.DEFERRED.name(), failed.attempt(), failed.reason() },
						"payment_id = ?", failed.paymentId().value());

			case PaymentAbandoned abandoned ->
				setStatus(abandoned.paymentId().value(), PaymentStatus.Status.ABANDONED, abandoned.reason());
		}
	}

	private void setStatus ( String paymentId, PaymentStatus.Status status, String detail ) {
		updateOnce(table(PAYMENTS),
				"status = ?, detail = ?", new Object[] { status.name(), detail },
				"payment_id = ?", paymentId);
	}

}
