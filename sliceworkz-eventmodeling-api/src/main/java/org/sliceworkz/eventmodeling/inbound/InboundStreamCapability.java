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

/**
 * Reaches what a bounded context does with its inbound stream.
 *
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 */
public interface InboundStreamCapability<INBOUND_EVENT_TYPE> {

	/**
	 * Feeding external events in, and reading the read models over the inbound stream. Hand each caller the
	 * narrow type it needs — {@link TranslationCapability} or
	 * {@link org.sliceworkz.eventmodeling.readmodels.ReadModelCapability} — rather than the whole of it.
	 */
	InboundCapabilities<INBOUND_EVENT_TYPE> inbound ( );

}
