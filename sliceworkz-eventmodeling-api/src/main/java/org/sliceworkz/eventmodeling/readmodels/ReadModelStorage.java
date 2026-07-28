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
package org.sliceworkz.eventmodeling.readmodels;

/**
 * Where an eventually consistent read model keeps its projected state.
 * <p>
 * The storage class is a property of the read model itself — it follows from where the read model
 * writes — and it is what determines how many instances of the bounded context have to project into
 * it:
 * <ul>
 *   <li>{@link #EPHEMERAL}: in-memory state, gone when the process ends. Every instance has its own
 *       copy, so every instance projects, and stale bookmarks from a previous process are discarded
 *       at startup so the model is rebuilt from the beginning.</li>
 *   <li>{@link #LOCAL}: durable but private to one instance (a local file, an embedded database, a
 *       per-instance schema). Every instance projects into its own copy and resumes from its own
 *       bookmark after a restart.</li>
 *   <li>{@link #SHARED}: durable storage all instances read and write (a database shared by the
 *       deployment). A single elected leader projects; the others just query.</li>
 * </ul>
 * Because storage and projection are decided by this one value, the combination that used to be
 * expressible — in-memory state projected on a single leader only, leaving every other instance
 * answering from an empty model — cannot be configured.
 */
public enum ReadModelStorage {

	EPHEMERAL("ephemeral"),
	LOCAL("local"),
	SHARED("shared");

	private final String label;

	private ReadModelStorage ( String label ) {
		this.label = label;
	}

	public String label ( ) {
		return label;
	}

	/**
	 * Whether every instance of the bounded context must run its own projector for this read model.
	 * Only {@link #SHARED} state is written once by the elected leader; ephemeral and local state
	 * exist per instance and so have to be projected by each of them.
	 */
	public boolean projectedOnEveryInstance ( ) {
		return this != SHARED;
	}

}
