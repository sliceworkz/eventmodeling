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
package org.sliceworkz.eventmodeling.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The overrides a caller asks for with one command execution: the ticked checkboxes of a form, each an
 * {@link OverrideRequest} naming a rule and, where the rule wants one, an explanation.
 * <p>
 * Overrides are an <em>input</em> of the command, exactly like the amount of a withdrawal: the endpoint that
 * builds the command from the request passes them in, and the command exposes them by implementing
 * {@link Overriding} — for a record, a component {@code Overrides overrides} is all it takes. A command
 * that does not implement {@link Overriding} overrides nothing, which is the safe default for everything
 * that builds commands without a user in front of it: automations, translators, retries, tests.
 * <pre>{@code
 * Overrides.none()
 * Overrides.of("no-overdraft")
 * Overrides.none().with("origin-of-funds-explained", "Proceeds of the sale of a car, invoice attached")
 * }</pre>
 * A rule may be named once; naming it twice is a malformed request and is refused with an
 * {@code IllegalArgumentException}.
 *
 * @param requests the individual override requests
 */
public record Overrides ( List<OverrideRequest> requests ) {

	private static final Overrides NONE = new Overrides(List.of());

	public Overrides {
		requests = ( requests == null ) ? List.of() : List.copyOf(requests);
		for ( int i = 0; i < requests.size(); i++ ) {
			for ( int j = i + 1; j < requests.size(); j++ ) {
				if ( requests.get(i).rule().equals(requests.get(j).rule()) ) {
					throw new IllegalArgumentException("rule '%s' is overridden twice in one request".formatted(requests.get(i).rule()));
				}
			}
		}
	}

	/**
	 * @return no overrides at all
	 */
	public static Overrides none ( ) {
		return NONE;
	}

	/**
	 * @param rules the ids of the rules to override, without explanation
	 * @return the overrides
	 */
	public static Overrides of ( String... rules ) {
		List<OverrideRequest> requests = new ArrayList<>();
		for ( String rule: rules ) {
			requests.add(OverrideRequest.of(rule));
		}
		return new Overrides(requests);
	}

	/**
	 * @param requests the override requests
	 * @return the overrides
	 */
	public static Overrides of ( OverrideRequest... requests ) {
		return new Overrides(List.of(requests));
	}

	/**
	 * The overrides a request body carried, as the list an HTTP payload record deserializes them into.
	 *
	 * @param requests the override requests; {@code null} (a body without any) is none
	 * @return the overrides
	 */
	public static Overrides of ( List<OverrideRequest> requests ) {
		return ( requests == null || requests.isEmpty() ) ? NONE : new Overrides(requests);
	}

	/**
	 * @param rule the id of a further rule to override
	 * @return these overrides plus that one, without explanation
	 */
	public Overrides with ( String rule ) {
		return with(OverrideRequest.of(rule));
	}

	/**
	 * @param rule the id of a further rule to override
	 * @param explanation why
	 * @return these overrides plus that one
	 */
	public Overrides with ( String rule, String explanation ) {
		return with(OverrideRequest.of(rule, explanation));
	}

	/**
	 * @param rule a further rule to override
	 * @return these overrides plus that one, without explanation
	 */
	public Overrides with ( BusinessRule rule ) {
		return with(OverrideRequest.of(rule.id()));
	}

	/**
	 * @param rule a further rule to override
	 * @param explanation why
	 * @return these overrides plus that one
	 */
	public Overrides with ( BusinessRule rule, String explanation ) {
		return with(OverrideRequest.of(rule.id(), explanation));
	}

	private Overrides with ( OverrideRequest request ) {
		List<OverrideRequest> all = new ArrayList<>(requests);
		all.add(request);
		return new Overrides(all);
	}

	/**
	 * @param ruleId the id of a rule
	 * @return the request overriding it, if any
	 */
	public Optional<OverrideRequest> requestFor ( String ruleId ) {
		return requests.stream().filter(r -> r.rule().equals(ruleId)).findFirst();
	}

	/**
	 * @return whether nothing is overridden
	 */
	public boolean isEmpty ( ) {
		return requests.isEmpty();
	}

}
