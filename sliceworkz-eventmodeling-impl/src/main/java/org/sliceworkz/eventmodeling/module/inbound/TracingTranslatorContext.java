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

import org.sliceworkz.eventmodeling.module.boundedcontext.BoundTracing;
import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.Execution;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * A {@link TranslatorContext} decorator that carries a {@link Tracing} into every capability call a
 * translator makes without one.
 * <p>
 * A translator used to raise its domain events through the tracing-less overloads, which substitute a
 * bare {@code Tracing.init(instance)} deeper down - so the events it raised carried only instance
 * tags: no actor, no channel, and, decisively, not the correlation id of the inbound event being
 * translated. This decorator is handed the tracing derived for exactly one translation (translator
 * name as actor, channel {@code "translation"}, the inbound event's correlation id) and fills it in,
 * so the domain events a translator raises are attributed to the flow that caused them. An overload
 * that already takes an explicit {@code Tracing} passes the caller's through untouched.
 * <p>
 * Not thread-safe and not meant to be held: one instance serves one translation.
 */
class TracingTranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> implements TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> {

	private final TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> delegate;
	private final Tracing tracing;

	TracingTranslatorContext ( TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> delegate, Tracing tracing ) {
		this.delegate = delegate;
		this.tracing = tracing;
	}

	@Override
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Execution execution ) {
		return BoundTracing.call(tracing, ( ) -> delegate.execute(command, execution));
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Execution execution ) {
		return BoundTracing.call(tracing, ( ) -> delegate.execute(command, execution));
	}

	@Override
	public Optional<EventReference> event ( DOMAIN_EVENT_TYPE event ) {
		return BoundTracing.call(tracing, ( ) -> delegate.event(event));
	}

	@Override
	public Optional<EventReference> event ( EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return BoundTracing.call(tracing, ( ) -> delegate.event(event));
	}

}
