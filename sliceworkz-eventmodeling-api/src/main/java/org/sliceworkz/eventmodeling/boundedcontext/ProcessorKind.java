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
package org.sliceworkz.eventmodeling.boundedcontext;

/**
 * The kind of projector-driven processor a {@link ProcessorStatus} describes. Together with the
 * processor's name this addresses one processor on {@link ProcessorAdminCapability#restartProcessor}
 * — names are unique per kind, not across kinds, so the kind is part of the address.
 * <p>
 * Automations are deliberately not a kind here: they run on a different processor with a richer
 * status and their own {@code AutomationAdminCapability}.
 */
public enum ProcessorKind {

	/** An eventually consistent read model's projector. */
	READ_MODEL,

	/** A translator's processor, reading the inbound stream. */
	TRANSLATOR,

	/** A dispatcher's processor, publishing the outbound stream. */
	DISPATCHER

}
