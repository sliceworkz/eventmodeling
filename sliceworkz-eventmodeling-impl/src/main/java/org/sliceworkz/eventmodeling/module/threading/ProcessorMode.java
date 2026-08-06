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
package org.sliceworkz.eventmodeling.module.threading;

/**
 * How a processor is meant to run, declared at registration and fixed for its lifetime — except for
 * {@link #STOPPED}, which start/stop and an automation's failure policy toggle it in and out of.
 * <p>
 * This is the <em>static</em> half of the decision whether a loop pass does work; the dynamic half is
 * {@link ProcessorInstanceMode}, which leader election flips at runtime. A pass runs when the
 * processor is not stopped <b>and</b> ({@code RUNNING_ON_ALL_INSTANCES} or this instance is the
 * elected leader for it).
 * <p>
 * Used to be a byte-identical nested enum in both {@code ProjectorProcessor} and
 * {@code AutomationProcessor}; hoisted here when leader election gave it a second reader.
 */
public enum ProcessorMode {

	/** Processing will not run at all, until {@code start()} puts the processor back. */
	STOPPED,

	/** Processing runs on the single instance elected leader for this processor. */
	RUNNING_ON_SINGLE_LEADER,

	/** Processing runs on every instance, untouched by leader election. */
	RUNNING_ON_ALL_INSTANCES,

}
