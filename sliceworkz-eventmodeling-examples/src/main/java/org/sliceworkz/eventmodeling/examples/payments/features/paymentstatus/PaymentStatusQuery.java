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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventmodeling.readmodels.sql.SqlReadModelQuery;

/**
 * The read side of {@link PaymentStatusProjector}: plain SQL over the tables it writes.
 * <p>
 * It is a separate object from the projector on purpose. The projector is handed to the framework,
 * which projects it on its own thread, and possibly on another instance altogether; this is what the
 * application holds and asks, on any instance, from any thread — every call takes a connection of its
 * own and sees only committed batches.
 * <p>
 * {@link #status} answers with the reference of the newest event the row reflects, so a caller that
 * holds the reference of its own write can tell whether the answer includes it
 * ({@code !mine.happenedAfter(result.upTo())}) instead of guessing. That reference says how far this
 * <em>row</em> has come, which is a lower bound on how far the read model has.
 */
public class PaymentStatusQuery extends SqlReadModelQuery {

	private static final String COLUMNS = """
			payment_id, iban, amount_in_cents, status, failed_attempts, detail,
			last_event_id, last_event_position, last_event_tx, last_event_index""";

	public PaymentStatusQuery ( DataSource dataSource ) {
		super(dataSource, PaymentStatusProjector.TABLE_PREFIX);
	}

	/** One payment's status and the event it reflects, or empty when the read model holds no such payment (yet). */
	public Optional<ReadModelResult<PaymentStatus>> status ( PaymentId paymentId ) {
		return querySingleWithRef(
				"SELECT %s FROM %s WHERE payment_id = ?".formatted(COLUMNS, table(PaymentStatusProjector.PAYMENTS)),
				PaymentStatusQuery::map, paymentId.value());
	}

	/** Every payment in the given status, in payment id order. */
	public List<PaymentStatus> withStatus ( PaymentStatus.Status status ) {
		return queryList(
				"SELECT %s FROM %s WHERE status = ? ORDER BY payment_id".formatted(COLUMNS, table(PaymentStatusProjector.PAYMENTS)),
				PaymentStatusQuery::map, status.name());
	}

	private static PaymentStatus map ( ResultSet rs ) throws SQLException {
		return new PaymentStatus(
				rs.getString("payment_id"),
				rs.getString("iban"),
				rs.getLong("amount_in_cents"),
				PaymentStatus.Status.valueOf(rs.getString("status")),
				rs.getInt("failed_attempts"),
				rs.getString("detail"));
	}

}
