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

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that decides whether a component may key a bookmark, used by all four registries
 * (read models, automations, translators, dispatchers).
 * <p>
 * A component's name ends up in {@link ProcessorIdentification#id()}, which is the reader name of the
 * bookmark recording how far that component has been projected — plus its metric tags and, for an
 * automation, the id {@code AutomationAdminCapability} addresses it by. Two properties are therefore
 * load-bearing, and neither is checked anywhere further down:
 * <ul>
 * <li><b>unique within its kind</b> — two components sharing a name share one bookmark, so each one
 *     advances it past events the other never saw. Nothing fails: the events are simply never
 *     handled, and for a dispatcher that means never published. This is the reason the check cannot
 *     be left to a naming convention</li>
 * <li><b>stable across restarts</b> — a name a class cannot supply the same way twice gives a fresh
 *     bookmark on every start, so the whole stream is handled again from the beginning at every boot.
 *     An anonymous class has no simple name at all; a generated one (a lambda, a proxy, a bytecode-
 *     generated subclass) has a name like {@code Foo$$Lambda/0x00007f...} that is regenerated per JVM
 *     run. For a dispatcher that is duplicate publishing to an external system on every restart</li>
 * </ul>
 * Both used to fail — where they failed at all — deeper down with a bare {@code id is required} that
 * named neither the component nor the reason.
 * <p>
 * A registry creates one of these per bounded context and feeds every component through it. Not
 * thread-safe: registration happens on the building thread.
 */
public class ProcessorNames {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorNames.class);

	private final String label;
	private final Set<String> seen = new HashSet<>();

	/**
	 * @param label how this kind of component is named in the error messages, e.g. {@code "dispatcher"}
	 */
	public static ProcessorNames of ( String label ) {
		return new ProcessorNames(label);
	}

	private ProcessorNames ( String label ) {
		this.label = label;
	}

	/**
	 * Validates a component whose identity is its class' simple name, and claims that name.
	 *
	 * @return the validated name
	 */
	public String claim ( Object component ) {
		return claim(component, component.getClass().getSimpleName());
	}

	/**
	 * Validates a component that supplies its own name — a read model through {@code readmodelName()} —
	 * and claims it.
	 * <p>
	 * The shape of the class is only held against it when the name it supplied <em>is</em> the class'
	 * simple name, i.e. when it did not override the default. An anonymous class returning a stable name
	 * of its own is perfectly able to key a bookmark and is accepted.
	 *
	 * @return the validated name
	 */
	public String claim ( Object component, String name ) {
		Class<?> componentClass = component.getClass();

		boolean nameIsTheClassName = name == null || name.isBlank() || name.equals(componentClass.getSimpleName());
		if ( nameIsTheClassName && ( componentClass.isAnonymousClass() || componentClass.isSynthetic() ) ) {
			throw new IllegalArgumentException(
				"%s %s must be a named class: its name identifies it and keys the bookmark recording its progress, which an anonymous class or lambda cannot provide stably"
					.formatted(label, componentClass.getName()));
		}

		if ( name == null || name.isBlank() ) {
			throw new IllegalArgumentException(
				"%s %s has no name: the name keys the bookmark recording its progress, so it cannot be blank - give it a name or make it a named class"
					.formatted(label, componentClass.getName()));
		}

		if ( !seen.add(name) ) {
			LOGGER.error("duplicate %s name '%s' registered".formatted(label, name));
			throw new IllegalArgumentException("duplicate %s name '%s' - bookmarks would collide".formatted(label, name));
		}

		return name;
	}

	/**
	 * Takes a name out of circulation without validating it, so a later {@link #claim(Object)} collides
	 * with it. Read models use this for the live models: those are projected on demand and key no
	 * bookmark of their own, so a collision among them is harmless — but an eventually consistent read
	 * model taking one of their names is not, since it is the name a read is addressed by.
	 */
	public void reserve ( String name ) {
		seen.add(name);
	}

}
