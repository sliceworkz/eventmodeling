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
 * Thrown when {@link TranslationCapability#translate} is invoked with an inbound event
 * for which no registered {@link Translator} matches.
 * <p>
 * Unlike the eventually-consistent {@code incoming(...)} path - which simply appends the
 * inbound event to the inbound stream and lets translators pick it up asynchronously - the
 * interactive {@code translate(...)} path runs the matching translators synchronously. When
 * no translator's {@link Translator#eventQuery()} matches the supplied event there is nothing
 * to run, which is treated as a programming/configuration error and surfaced as this exception.
 */
public class NoTranslatorRegisteredException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public NoTranslatorRegisteredException ( String message ) {
		super(message);
	}

}
