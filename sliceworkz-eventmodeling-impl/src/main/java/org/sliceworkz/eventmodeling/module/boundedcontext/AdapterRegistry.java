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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry that stores adapter-to-port bindings with optional qualifications.
 * <p>
 * Adapters are registered via {@link #register(Object, Class, String)} and
 * looked up via {@link #lookup(Class, String)}.
 */
class AdapterRegistry {

	static final String DEFAULT_QUALIFICATION = "__default__";

	private final Map<PortKey, Object> adapters = new LinkedHashMap<>();

	/**
	 * Registers an adapter for the given port type and qualification.
	 *
	 * @param adapter the adapter instance
	 * @param portType the port interface
	 * @param qualification the qualification (use {@link #DEFAULT_QUALIFICATION} for default)
	 * @throws IllegalArgumentException if the adapter is not assignable to the port type,
	 *         or if a binding already exists for this port/qualification
	 */
	void register(Object adapter, Class<?> portType, String qualification) {
		if (!portType.isAssignableFrom(adapter.getClass())) {
			throw new IllegalArgumentException(
					"Adapter of type '%s' is not assignable to port '%s'"
							.formatted(adapter.getClass().getName(), portType.getName()));
		}
		var key = new PortKey(portType, qualification);
		if (adapters.containsKey(key)) {
			String qualDisplay = DEFAULT_QUALIFICATION.equals(qualification) ? "default" : "'" + qualification + "'";
			throw new IllegalArgumentException(
					"An adapter is already registered for port '%s' with qualification %s"
							.formatted(portType.getName(), qualDisplay));
		}
		adapters.put(key, adapter);
	}

	/**
	 * Looks up the adapter for the given port type and qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface
	 * @param qualification the qualification (use {@link #DEFAULT_QUALIFICATION} for default)
	 * @return the adapter instance, cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port/qualification
	 */
	@SuppressWarnings("unchecked")
	<T> T lookup(Class<T> portType, String qualification) {
		var key = new PortKey(portType, qualification);
		Object adapter = adapters.get(key);
		if (adapter == null) {
			String qualDisplay = DEFAULT_QUALIFICATION.equals(qualification) ? "default" : "'" + qualification + "'";
			throw new IllegalStateException(
					"No adapter registered for port '%s' with qualification %s"
							.formatted(portType.getName(), qualDisplay));
		}
		return (T) adapter;
	}

	private record PortKey(Class<?> portType, String qualification) {}

}
