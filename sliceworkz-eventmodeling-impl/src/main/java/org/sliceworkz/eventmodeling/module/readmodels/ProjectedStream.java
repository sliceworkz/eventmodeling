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
package org.sliceworkz.eventmodeling.module.readmodels;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextStreams;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.module.threading.ProcessorIdentification;

/**
 * The stream of a bounded context a read model is projected from: the domain stream, which is where every
 * read model registered on the builder itself reads, or the inbound or outbound stream, for one registered on
 * {@code builder.inbound()} or {@code builder.outbound()}.
 * <p>
 * Each has a processor type of its own, so a read model over the inbound stream and one over the domain stream
 * that share a name keep two bookmarks; the domain one keeps {@code readmodel}, which every bookmark placed
 * before the other two existed is named under. And each has a {@link ProcessorKind} of its own, since an
 * operator addresses a processor by kind and name, and names are unique per kind.
 */
public enum ProjectedStream {

	DOMAIN ( BoundedContextStreams.DOMAIN, ProcessorIdentification.TYPE_READMODEL, ProcessorKind.READ_MODEL ),
	INBOUND ( BoundedContextStreams.INBOUND, ProcessorIdentification.TYPE_INBOUND_READMODEL, ProcessorKind.INBOUND_READ_MODEL ),
	OUTBOUND ( BoundedContextStreams.OUTBOUND, ProcessorIdentification.TYPE_OUTBOUND_READMODEL, ProcessorKind.OUTBOUND_READ_MODEL );

	private final String purpose;
	private final String processorType;
	private final ProcessorKind processorKind;

	ProjectedStream ( String purpose, String processorType, ProcessorKind processorKind ) {
		this.purpose = purpose;
		this.processorType = processorType;
		this.processorKind = processorKind;
	}

	/** The stream's purpose, as {@link BoundedContextStreams} names it. */
	public String purpose ( ) {
		return purpose;
	}

	/** The purpose as the read model events and the slice inventory carry it: {@code null} for the domain stream. */
	public String reported ( ) {
		return this == DOMAIN ? null : purpose;
	}

	/** The {@code type} of the {@link ProcessorIdentification} keying such a read model's bookmark. */
	public String processorType ( ) {
		return processorType;
	}

	/** The kind an operator addresses such a read model's projector by. */
	public ProcessorKind processorKind ( ) {
		return processorKind;
	}

}
