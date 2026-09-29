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

import java.util.function.Function;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Calls the gateway from inside {@code execute} — so the call is attributed to this command — and records
 * what it answered. Lets whatever the gateway throws propagate, as a command would.
 */
public class CallGatewayCommand implements Command<MockDomainEvent> {

	private final GatewayPort gateway;
	private final Function<GatewayPort, String> call;

	public CallGatewayCommand ( GatewayPort gateway, Function<GatewayPort, String> call ) {
		this.gateway = gateway;
		this.call = call;
	}

	@Override
	public void execute ( CommandContext<MockDomainEvent,MockDomainEvent> context ) {
		String answer = call.apply(gateway);
		context.noDecisionModels().raiseEvent(new FirstDomainEvent(answer), Tags.none());
	}

}
