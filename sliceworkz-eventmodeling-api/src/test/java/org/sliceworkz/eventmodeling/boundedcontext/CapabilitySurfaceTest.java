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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.EventTypes;
import org.sliceworkz.eventmodeling.aggregates.AggregateCapability;
import org.sliceworkz.eventmodeling.automation.AutomationAdminCapability;
import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;
import org.sliceworkz.eventmodeling.inbound.TranslationCapability;
import org.sliceworkz.eventmodeling.readmodels.ReadModelCapability;

/**
 * Pins that every capability a bounded context carries is filed under an audience.
 * <p>
 * {@link CapabilityAudienceTest} pins that narrowing to an audience leaves the others out; this pins
 * the other half, which no compile probe can reach — that there is nothing on {@link BoundedContext}
 * that <em>only</em> the whole surface carries. A method declared straight on {@link BoundedContext}
 * or on {@link AllCapabilities}, or a new leaf capability added to the extends list without being
 * filed under {@link ApplicationCapabilities}, {@link OperationsCapabilities} or one of the audiences
 * beside them, is reachable by every holder of the context and by no narrower reference — so the
 * narrowing quietly stops covering it, and nothing fails, because the extra method is simply never
 * called through the narrow type.
 * <p>
 * Adding a capability is therefore meant to fail here, and the fix is to decide whose it is: put it
 * in an audience, or add an audience for it and say in {@code WHO-MAY-DO-WHAT.md} who holds it.
 */
public class CapabilitySurfaceTest {

	/** the leaves, each one concern, each reachable through a reference narrower than the context */
	private static final List<Class<?>> AUDIENCE_CAPABILITIES = List.of(
			// application: decide and read
			CommandExecutionCapability.class,
			ReadModelCapability.class,
			AggregateCapability.class,
			// the inbound edge
			TranslationCapability.class,
			// the operator
			AutomationAdminCapability.class,
			ProcessorAdminCapability.class,
			// audiences of their own
			ProvidedEventCapability.class,
			PrivacyCapability.class,
			// the owner's, and deliberately nobody else's
			LifecycleCapability.class,
			PortsCapability.class,
			FeatureSliceCapabilities.class);

	@Test
	void allCapabilitiesDeclaresNoMethodOfItsOwn ( ) {
		assertEquals(List.of(), names(AllCapabilities.class.getDeclaredMethods()),
				"AllCapabilities is a composition of audiences, so a method declared on it is one no"
				+ " narrower reference can reach: move it into an audience interface");
	}

	@Test
	void allCapabilitiesComposesTheAudiencesAndNothingElse ( ) {
		assertEquals(
				Set.of(ApplicationCapabilities.class, TranslationCapability.class, OperationsCapabilities.class,
						ProvidedEventCapability.class, PrivacyCapability.class, LifecycleCapability.class,
						PortsCapability.class, FeatureSliceCapabilities.class),
				Set.of(AllCapabilities.class.getInterfaces()),
				"the owner surface gained or lost a direct superinterface: WHO-MAY-DO-WHAT.md names the"
				+ " audiences, and adding one here without naming it there leaves callers nothing to narrow to");
	}

	@Test
	void theApplicationSurfaceIsTheTwoCheckedWritePathsAndTheRead ( ) {
		assertEquals(
				Set.of(CommandExecutionCapability.class, ReadModelCapability.class, AggregateCapability.class),
				Set.of(ApplicationCapabilities.class.getInterfaces()));
		assertEquals(List.of(), names(ApplicationCapabilities.class.getDeclaredMethods()));
	}

	@Test
	void theOperatorSurfaceIsTheTwoAdminCapabilities ( ) {
		assertEquals(
				Set.of(AutomationAdminCapability.class, ProcessorAdminCapability.class),
				Set.of(OperationsCapabilities.class.getInterfaces()));
		assertEquals(List.of(), names(OperationsCapabilities.class.getDeclaredMethods()));
	}

	@Test
	void everyMethodOnABoundedContextComesFromACapabilityACallerCanNarrowTo ( ) {
		Set<String> unfiled = new TreeSet<>();
		for ( Method method : BoundedContext.class.getMethods() ) {
			if ( Modifier.isStatic(method.getModifiers()) ) continue;
			Class<?> declaring = method.getDeclaringClass();
			// name() identifies the context and is the one method that is the context's own
			if ( declaring == BoundedContext.class || declaring == EventTypes.class ) continue;
			if ( AUDIENCE_CAPABILITIES.contains(declaring) ) continue;
			unfiled.add(declaring.getSimpleName() + "." + method.getName());
		}
		assertTrue(unfiled.isEmpty(),
				() -> "reachable on a bounded context and on no narrower reference: " + unfiled
				+ ". Decide whose capability this is and file it under an audience");
	}

	@Test
	void theContextItselfDeclaresOnlyItsName ( ) {
		assertEquals(List.of("name"), names(BoundedContext.class.getDeclaredMethods()));
	}

	private static List<String> names ( Method[] methods ) {
		return Arrays.stream(methods)
				.filter(m -> !m.isSynthetic())
				.filter(m -> !Modifier.isStatic(m.getModifiers()))
				.filter(m -> Modifier.isAbstract(m.getModifiers()))
				.map(Method::getName)
				.distinct()
				.sorted()
				.collect(Collectors.toList());
	}
}
