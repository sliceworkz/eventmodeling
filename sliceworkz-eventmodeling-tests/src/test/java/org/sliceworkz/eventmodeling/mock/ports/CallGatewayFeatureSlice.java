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
package org.sliceworkz.eventmodeling.mock.ports;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Asks for the gateway while it is configured, the way a slice takes its ports: the inventory on
 * {@code BoundedContextStarting} names it as a user of the port, and the command in its package is reported
 * with it as its slice.
 */
@FeatureSlice(type = Type.STATE_CHANGE, context = "mock", chapter = "Ports")
public class CallGatewayFeatureSlice implements Slice<Mock> {

	/** What {@code configureCommand} was handed for the port — the proxy for a monitored one. */
	public static volatile GatewayPort handedOut;

	/** What {@code startCommand} was handed for the port — the port as the endpoints a slice wires there use it. */
	public static volatile GatewayPort startedWith;

	@Override
	public void configureCommand ( BoundedContextBuilder<Mock> builder ) {
		handedOut = builder.port(GatewayPort.class);
		builder.command(CallGatewayCommand.class);
	}

	/** The context {@code startCommand} was handed — what an endpoint keeps to look its ports up per request. */
	public static volatile Mock startedOn;

	@Override
	public void startCommand ( Mock boundedContext ) {
		startedOn = boundedContext;
		startedWith = boundedContext.port(GatewayPort.class);
	}

}
