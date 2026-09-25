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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Events → {@link PaymentStatusProjector} → SQL tables ← {@link PaymentStatusQuery}.
 * <p>
 * The database is a port, like the payment gateway: the application binds the {@code DataSource} its
 * read models live in with {@code .adapter(dataSource).forPort(DataSource.class, READ_MODELS)}, and
 * constructs a {@link PaymentStatusQuery} over the same one to read with. The qualification keeps this
 * {@code DataSource} apart from any other the application binds.
 * <p>
 * The tables are ensured here rather than left to the first batch, so a query issued before anything
 * has been projected finds an empty table instead of a missing one.
 */
@FeatureSlice(type = Type.STATE_READ, context = "payments", chapter = "Executing Payments",
	tags = {"sql", "read-model"})
public class PaymentStatusFeatureSlice implements Slice<Payments> {

	/** The qualification of the {@code DataSource} port the read models are kept in. */
	public static final String READ_MODELS = "readmodels";

	@Override
	public void configureQuery ( BoundedContextBuilder<Payments> builder ) {
		PaymentStatusProjector projector = new PaymentStatusProjector(builder.port(DataSource.class, READ_MODELS));
		projector.ensureTables();
		builder.readmodel(projector).eventuallyConsistent();
	}

}
