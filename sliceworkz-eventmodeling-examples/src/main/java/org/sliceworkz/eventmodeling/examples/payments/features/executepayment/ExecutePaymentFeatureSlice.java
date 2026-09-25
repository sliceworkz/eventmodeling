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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.payments.Payments;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Events → {@link PaymentsToExecuteTodoList} → {@link ExecutePaymentAutomation} → gateway → Events.
 * <p>
 * The slice wires the whole feature itself, including the part that reaches outside the context. The
 * automation needs a {@link PaymentGateway}, and which gateway that is — a real provider's client, the
 * simulated one the example runs on — is the application's decision, not the slice's: the application
 * binds an adapter to the port on the builder,
 * <pre>
 *   .adapter(gateway).forPort(PaymentGateway.class)
 * </pre>
 * and the slice asks for the port by its interface. The dependency stays declared where it is used, the
 * infrastructure stays chosen where it is deployed, and a build that binds no adapter for a port a
 * deployed slice asks for fails at {@code build()} naming the port, instead of the automation failing
 * on its first item. An instance that does not deploy automations never asks, so it need not bind
 * one. The alternative — the application constructing the automation and registering it beside the
 * scanned slices — loses because the slice is then a slice in name only: its wiring lives in another
 * file, and every deployment has to repeat it. See {@code PaymentsExample} for the application side.
 */
@FeatureSlice(type = Type.AUTOMATION, context = "payments", chapter = "Executing Payments",
	tags = {"automation", "outbound", "failure-handling"})
public class ExecutePaymentFeatureSlice implements Slice<Payments> {

	@Override
	public void configureAutomation ( BoundedContextBuilder<Payments> builder ) {
		PaymentGateway gateway = builder.port(PaymentGateway.class);
		var todoList = new PaymentsToExecuteTodoList();
		builder.readmodel(todoList).eventuallyConsistent();
		builder.automation(new ExecutePaymentAutomation(todoList, gateway));
	}

	@Override
	public void configureCommand ( BoundedContextBuilder<Payments> builder ) {
		// declarative only: the automation executes the command, and this lists it among the slice's
		// members from startup rather than from its first execution
		builder.command(AnnouncePaymentCommand.class);
	}

}
