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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorAdminCapability;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
import org.sliceworkz.eventmodeling.boundedcontext.ProcessorStatus;

/**
 * The admin view over one module's {@link ProjectorProcessor}s: statuses and restart-by-name, in the
 * shape {@link ProcessorAdminCapability} serves. One instance per module, because the processor
 * itself deliberately does not know whether it projects a read model, a translator or a dispatcher —
 * the module knows, and registers each processor here together with the component class the status
 * reports.
 */
public final class ProjectorProcessorAdmin {

	private record Managed ( ProjectorProcessor<?> processor, String componentClass ) { }

	private final ProcessorKind kind;
	private final String boundedContext;
	private final Map<String, Managed> byName = new LinkedHashMap<>();

	public ProjectorProcessorAdmin ( ProcessorKind kind, String boundedContext ) {
		this.kind = kind;
		this.boundedContext = boundedContext;
	}

	/** Registers a processor under its name, with the simple name of the component it projects. */
	public void register ( ProjectorProcessor<?> processor, String componentClass ) {
		byName.put(processor.identification().id(), new Managed(processor, componentClass));
	}

	/** One status per registered processor, in registration order. */
	public List<ProcessorStatus> statuses ( ) {
		List<ProcessorStatus> result = new ArrayList<>(byName.size());
		byName.forEach((name, managed) -> {
			ProjectorProcessor.ProcessorSnapshot snapshot = managed.processor().snapshot();
			result.add(new ProcessorStatus(
					kind,
					name,
					managed.componentClass(),
					managed.processor().identification().storage().label(),
					snapshot.running(),
					snapshot.leader(),
					snapshot.consecutiveFailedRuns(),
					BoundedContextEvent.Failure.of(snapshot.lastFailure()),
					BoundedContextEvent.Failure.of(snapshot.stoppedBy())));
		});
		return result;
	}

	/**
	 * Restarts the named processor if it is stopped.
	 *
	 * @see ProcessorAdminCapability#restartProcessor(ProcessorKind, String)
	 */
	public boolean restart ( String name ) {
		Managed managed = byName.get(name);
		if ( managed == null ) {
			throw new IllegalArgumentException("no %s processor '%s' is registered on bounded context '%s', known are %s".formatted(
					kind, name, boundedContext, byName.keySet().stream().toList()));
		}
		return managed.processor().restart();
	}

}
