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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.module.ports.MonitoredPort;
import org.sliceworkz.eventmodeling.module.ports.PortCallerScope;
import org.sliceworkz.eventmodeling.module.ports.PortReporter;
import org.sliceworkz.eventmodeling.ports.PortCaller;
import org.sliceworkz.eventmodeling.ports.PortMonitoring;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Registry that stores adapter-to-port bindings with optional qualifications.
 * <p>
 * Adapters are registered via {@link #register(Object, Class, String, PortMonitoring)} and looked up via
 * {@link #lookup(Class, String)}. A monitored binding is looked up as its {@link MonitoredPort} proxy —
 * always the same one, so a slice taking the port while it is configured holds what the running context
 * reports on — and an unmonitored one as the adapter itself.
 */
class AdapterRegistry {

	static final String DEFAULT_QUALIFICATION = "__default__";

	private final Map<PortKey, Binding> adapters = new LinkedHashMap<>();

	/**
	 * Registers an adapter for the given port type and qualification.
	 *
	 * @param adapter the adapter instance
	 * @param portType the port interface
	 * @param qualification the qualification (use {@link #DEFAULT_QUALIFICATION} for default)
	 * @param monitoring how its calls are reported, {@code null} for not at all
	 * @throws IllegalArgumentException if the adapter is not assignable to the port type,
	 *         if a binding already exists for this port/qualification, or if a monitored port is not an
	 *         interface or does not declare a method to summarize
	 */
	void register(Object adapter, Class<?> portType, String qualification, PortMonitoring monitoring) {
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
		MonitoredPort monitored = monitoring == null ? null : MonitoredPort.of(portType, displayed(qualification), adapter, monitoring);
		adapters.put(key, new Binding(adapter, monitored, monitored == null ? adapter : monitored.proxy(), new LinkedHashSet<>(), new HashMap<>()));
	}

	void register(Object adapter, Class<?> portType, String qualification) {
		register(adapter, portType, qualification, null);
	}

	/**
	 * Looks up the adapter for the given port type and qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface
	 * @param qualification the qualification (use {@link #DEFAULT_QUALIFICATION} for default)
	 * @return the adapter instance — or the monitored proxy in front of it — cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port/qualification
	 */
	<T> T lookup(Class<T> portType, String qualification) {
		return lookup(portType, qualification, null);
	}

	/**
	 * Looks a port up on behalf of a feature slice, remembering that the slice asked for it.
	 *
	 * @param sliceName the slice being configured, {@code null} outside a slice's configuration
	 */
	@SuppressWarnings("unchecked")
	<T> T lookup(Class<T> portType, String qualification, String sliceName) {
		var key = new PortKey(portType, qualification);
		Binding binding = adapters.get(key);
		if (binding == null) {
			String qualDisplay = DEFAULT_QUALIFICATION.equals(qualification) ? "default" : "'" + qualification + "'";
			throw new IllegalStateException(
					"No adapter registered for port '%s' with qualification %s"
							.formatted(portType.getName(), qualDisplay));
		}
		if (sliceName != null) {
			synchronized (binding.slices()) {
				binding.slices().add(sliceName);
			}
		}
		return (T) binding.handedOut();
	}

	/**
	 * Looks a port up through the context a feature slice was handed in its {@code start...} methods: a
	 * monitored port comes back as a proxy that attributes the calls made through it to that slice whenever
	 * no component is running on the calling thread — which is what a REST endpoint the slice wires there
	 * is. One proxy per slice and binding, so every lookup of a slice gets the same one.
	 * <p>
	 * The slice is not added to the binding's inventory: that is announced by {@code BoundedContextStarting},
	 * before any slice is started, and a lookup made per request would otherwise list a slice there only
	 * from the first request after a restart on. Who calls a port through the proxy is
	 * on every call instead.
	 */
	@SuppressWarnings("unchecked")
	<T> T lookupForSlice(Class<T> portType, String qualification, Slice<?> slice) {
		Object handedOut = lookup(portType, qualification, null);
		Binding binding = adapters.get(new PortKey(portType, qualification));
		if (binding.monitored() == null) {
			return (T) handedOut;
		}
		synchronized (binding.sliceProxies()) {
			return (T) binding.sliceProxies().computeIfAbsent(slice.name(), name -> binding.monitored().proxyFor(
					new PortCallerScope.Current(PortCaller.slice(name), slice.getClass(), null)));
		}
	}

	/**
	 * Starts reporting the calls of every monitored port to the given reporter.
	 *
	 * @return whether any port summarizes its calls, i.e. whether the reporter needs its thread
	 */
	boolean attach(PortReporter reporter) {
		boolean summarizing = false;
		for (Binding binding : adapters.values()) {
			if (binding.monitored() != null) {
				binding.monitored().attach(reporter);
				PortMonitoring monitoring = binding.monitored().monitoring();
				summarizing |= monitoring.mode() == PortMonitoring.Mode.SUMMARIZED || !monitoring.summarizedMethods().isEmpty();
			}
		}
		return summarizing;
	}

	/**
	 * @return every binding, as the {@code BoundedContextStarting} announcing the context lists it
	 */
	List<BoundedContextEvent.PortBinding> describe() {
		List<BoundedContextEvent.PortBinding> described = new ArrayList<>();
		for (Map.Entry<PortKey, Binding> entry : adapters.entrySet()) {
			Class<?> portType = entry.getKey().portType();
			Binding binding = entry.getValue();
			PortMonitoring monitoring = binding.monitored() == null ? null : binding.monitored().monitoring();
			Set<String> summarized = monitoring == null ? Set.of()
					: monitoring.mode() == PortMonitoring.Mode.SUMMARIZED ? Set.of("*") : monitoring.summarizedMethods();
			Set<String> slices;
			synchronized (binding.slices()) {
				slices = Set.copyOf(binding.slices());
			}
			described.add(new BoundedContextEvent.PortBinding(portType.getSimpleName(), portType.getName(),
					displayed(entry.getKey().qualification()), binding.adapter().getClass().getName(),
					monitoring != null, monitoring == null ? null : monitoring.toString(), summarized, slices));
		}
		return described;
	}

	private static String displayed(String qualification) {
		return DEFAULT_QUALIFICATION.equals(qualification) ? null : qualification;
	}

	private record PortKey(Class<?> portType, String qualification) {}

	private record Binding(Object adapter, MonitoredPort monitored, Object handedOut, Set<String> slices, Map<String, Object> sliceProxies) {}

}
