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

import java.util.Optional;

import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.Execution;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;

// methods available to a Translator
public class TranslatorContextImpl<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements TranslatorContext<INBOUND_EVENT_TYPE, DOMAIN_EVENT_TYPE> {
	// TOOD should we allow CommandsCapability?  Or only ProvidedEvents?

	private AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> delegate;
	
	public TranslatorContextImpl ( AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> delegate ) {
		this.delegate = delegate;
	}

	@Override
	public Optional<EventReference> execute(Command<DOMAIN_EVENT_TYPE> command, Execution execution ) {
		return delegate.execute(command, execution);
	}

	@Override
	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute(CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Execution execution ) {
		return delegate.execute(command, execution);
	}

	@Override
	public Optional<EventReference> event(DOMAIN_EVENT_TYPE event) {
		return delegate.event(event);
	}

	@Override
	public Optional<EventReference> event ( EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return delegate.event(event);
	}

}