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
package org.sliceworkz.eventmodeling.module.automation;

import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

public class AutomationContextImpl<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	private final AllCapabilities<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> delegate;
	private final Tracing tracing;

	public AutomationContextImpl ( AllCapabilities<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> delegate, Tracing tracing ) {
		this.delegate = delegate;
		this.tracing = tracing;
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command) {
		return delegate.execute(command, tracing);
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command, Tracing tracing) {
		return delegate.execute(command, tracing);
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command) {
		return delegate.execute(command, tracing);
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, Tracing tracing) {
		return delegate.execute(command, tracing);
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> execute(OutboundCommand<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> command, String idempotencyKey, Tracing tracing) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute(CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command) {
		return delegate.execute(command, tracing);
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute(CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing) {
		return delegate.execute(command, tracing);
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute(CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute(CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing) {
		return delegate.execute(command, idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tracing tracing) {
		return delegate.event(event, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event) {
		return delegate.event(event, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags) {
		return delegate.event(event, tags, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags, Tracing tracing) {
		return delegate.event(event, tags, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, String idempotencyKey) {
		return delegate.event(event, Tags.none(), idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags, String idempotencyKey) {
		return delegate.event(event, tags, idempotencyKey, tracing);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event, Tags tags, String idempotencyKey, Tracing tracing) {
		return delegate.event(event, tags, idempotencyKey, tracing);
	}

}
