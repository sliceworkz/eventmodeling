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
package org.sliceworkz.eventmodeling.inbound;

import org.sliceworkz.eventmodeling.readmodels.ReadModelCapability;

/**
 * What a bounded context does with its inbound stream, as {@code context.inbound()} hands it out: feeding
 * external events in ({@link TranslationCapability}: {@code incoming}, {@code translate}) and reading the
 * read models registered on {@code builder.inbound()} ({@link ReadModelCapability}: {@code read}).
 * <p>
 * Those are two audiences — the adapter feeding the domain, and a screen showing what came in — and each is
 * meant to hold only its own. Narrowing costs a reference type, as everywhere on the capability surface:
 * <pre>{@code
 * TranslationCapability<BankingInboundEvent> edge   = banking.inbound();
 * ReadModelCapability<BankingInboundEvent>   screen = banking.inbound();
 * }</pre>
 * {@code read} here reads a read model over the inbound stream; a read model over the domain stream is read on
 * the context itself, and passing one here does not compile.
 *
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 */
public interface InboundCapabilities<INBOUND_EVENT_TYPE> extends
	TranslationCapability<INBOUND_EVENT_TYPE>,
	ReadModelCapability<INBOUND_EVENT_TYPE> {

}
