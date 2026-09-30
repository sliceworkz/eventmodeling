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
package org.sliceworkz.eventmodeling.module.ports;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.sliceworkz.eventmodeling.module.timing.Elapsed;
import org.sliceworkz.eventmodeling.ports.PortMonitoring;

/**
 * The proxy a monitored port is handed out as: every call is timed, sorted into returned, rejected (a
 * business exception) or failed, and handed to the context's {@link PortReporter} — then answered exactly as
 * the adapter answered it. What the adapter threw reaches the caller unchanged: the reflective
 * {@link InvocationTargetException} is unwrapped, so a checked exception the port declares arrives as itself
 * rather than as an {@code UndeclaredThrowableException}.
 * <p>
 * The proxy is made when the adapter is bound, before the context — and so the reporter — exists, because a
 * feature slice takes its port from the builder while it is configured and keeps the reference. Until
 * {@link #attach(PortReporter)} a call passes straight through, unreported. {@code equals},
 * {@code hashCode} and {@code toString} are the proxy's own and never reported: they are not calls to the
 * port, and a proxy used as a map key must not be equal to the adapter behind it one moment and not the next.
 */
public final class MonitoredPort implements InvocationHandler {

	private final Class<?> portType;
	private final String qualification;
	private final Object adapter;
	private final PortMonitoring monitoring;

	private volatile PortReporter reporter;

	private MonitoredPort ( Class<?> portType, String qualification, Object adapter, PortMonitoring monitoring ) {
		this.portType = portType;
		this.qualification = qualification;
		this.adapter = adapter;
		this.monitoring = monitoring;
	}

	/**
	 * Validates a monitored binding and makes its proxy.
	 *
	 * @param qualification {@code null} for the default qualification
	 * @throws IllegalArgumentException for a port type that is not an interface, or a summarized method name
	 *         the port does not declare
	 */
	public static MonitoredPort of ( Class<?> portType, String qualification, Object adapter, PortMonitoring monitoring ) {
		if ( !portType.isInterface() ) {
			throw new IllegalArgumentException(("port '%s' cannot be monitored: only an interface can be, since the calls are intercepted "
					+ "by a java.lang.reflect.Proxy. Bind it without monitored(), or put an interface in front of it").formatted(portType.getName()));
		}
		for ( String method : monitoring.summarizedMethods() ) {
			boolean declared = false;
			for ( Method m : portType.getMethods() ) {
				if ( m.getName().equals(method) ) {
					declared = true;
					break;
				}
			}
			if ( !declared ) {
				throw new IllegalArgumentException("port '%s' has no method '%s' to summarize".formatted(portType.getName(), method));
			}
		}
		return new MonitoredPort(portType, qualification, adapter, monitoring);
	}

	/** @return the proxy to hand out for the port */
	public Object proxy ( ) {
		return Proxy.newProxyInstance(portType.getClassLoader(), new Class<?>[] { portType }, this);
	}

	/**
	 * A proxy reporting its calls as made by the given caller whenever no component is running on the
	 * calling thread: the port as handed to a feature slice while it is started, so the endpoints it wires
	 * report as that slice rather than as unattributed. A component running on the thread still wins —
	 * a command's own call through the same reference is the command's.
	 */
	public Object proxyFor ( PortCallerScope.Current fallback ) {
		return Proxy.newProxyInstance(portType.getClassLoader(), new Class<?>[] { portType },
				( proxy, method, args ) -> invoke(proxy, method, args, fallback));
	}

	/** Starts reporting calls to the given reporter. */
	public void attach ( PortReporter reporter ) {
		this.reporter = reporter;
	}

	public Class<?> portType ( ) {
		return portType;
	}

	public String qualification ( ) {
		return qualification;
	}

	public PortMonitoring monitoring ( ) {
		return monitoring;
	}

	@Override
	public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {
		return invoke(proxy, method, args, null);
	}

	private Object invoke ( Object proxy, Method method, Object[] args, PortCallerScope.Current fallback ) throws Throwable {
		if ( method.getDeclaringClass() == Object.class ) {
			return switch ( method.getName() ) {
				case "equals" -> proxy == args[0];
				case "hashCode" -> System.identityHashCode(proxy);
				case "toString" -> "monitored " + portType.getSimpleName() + " -> " + adapter;
				default -> invokeAdapter(method, args);
			};
		}
		PortReporter current = reporter;
		if ( current == null ) {
			return invokeAdapter(method, args);
		}
		PortReporter.Call call = current.start(this, method.getName(), fallback);
		long started = Elapsed.start();
		try {
			Object result = invokeAdapter(method, args);
			call.returned(Elapsed.microsSince(started));
			return result;
		} catch ( Throwable thrown ) {
			call.threw(thrown, Elapsed.microsSince(started));
			throw thrown;
		}
	}

	private Object invokeAdapter ( Method method, Object[] args ) throws Throwable {
		try {
			return method.invoke(adapter, args);
		} catch ( InvocationTargetException e ) {
			throw e.getCause();
		} catch ( IllegalAccessException notPublic ) {
			// a port interface that is not public: the proxy in its package reaches it, reflection here does not
			method.setAccessible(true);
			try {
				return method.invoke(adapter, args);
			} catch ( InvocationTargetException e ) {
				throw e.getCause();
			}
		}
	}

}
