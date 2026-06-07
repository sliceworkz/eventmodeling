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
package org.sliceworkz.eventmodeling.module.inbound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * A {@link TranslatorContext} decorator used by the interactive {@code translate(...)} path.
 * <p>
 * It delegates every capability call to a wrapped {@link TranslatorContext} (the regular bounded
 * context capabilities) while recording the {@link EventReference} of every domain event raised -
 * whether provided directly via {@code event(...)} or produced by a command executed via
 * {@code execute(...)}. The collected references are returned to the caller of {@code translate(...)}.
 * <p>
 * This decorator is not thread-safe; it is intended to be used for the duration of a single
 * synchronous {@code translate(...)} invocation.
 */
class CapturingTranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> implements TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> {

	private final TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> delegate;
	private final List<EventReference> references = new ArrayList<>();

	CapturingTranslatorContext ( TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> delegate ) {
		this.delegate = delegate;
	}

	List<EventReference> references ( ) {
		return Collections.unmodifiableList(references);
	}

	private Optional<EventReference> capture ( Optional<EventReference> reference ) {
		reference.ifPresent(references::add);
		return reference;
	}

	private <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> capture ( CommandExecutionResult<RESPONSE_TYPE> result ) {
		result.eventReference().ifPresent(references::add);
		return result;
	}

	@Override
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command ) {
		return capture(delegate.execute(command));
	}

	@Override
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return capture(delegate.execute(command, tracing));
	}

	@Override
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey ) {
		return capture(delegate.execute(command, idempotencyKey));
	}

	@Override
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return capture(delegate.execute(command, idempotencyKey, tracing));
	}

	@Override
	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> command ) {
		return capture(delegate.execute(command));
	}

	@Override
	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return capture(delegate.execute(command, tracing));
	}

	@Override
	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> command, String idempotencyKey ) {
		return capture(delegate.execute(command, idempotencyKey));
	}

	@Override
	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return capture(delegate.execute(command, idempotencyKey, tracing));
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command ) {
		return capture(delegate.execute(command));
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing ) {
		return capture(delegate.execute(command, tracing));
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey ) {
		return capture(delegate.execute(command, idempotencyKey));
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return capture(delegate.execute(command, idempotencyKey, tracing));
	}

	@Override
	public Optional<EventReference> event ( DOMAIN_EVENT_TYPE event ) {
		return capture(delegate.event(event));
	}

	@Override
	public Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tracing tracing ) {
		return capture(delegate.event(event, tracing));
	}

	@Override
	public Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
		return capture(delegate.event(event, tags));
	}

	@Override
	public Optional<EventReference> event ( DOMAIN_EVENT_TYPE event, Tags tags, Tracing tracing ) {
		return capture(delegate.event(event, tags, tracing));
	}

}
