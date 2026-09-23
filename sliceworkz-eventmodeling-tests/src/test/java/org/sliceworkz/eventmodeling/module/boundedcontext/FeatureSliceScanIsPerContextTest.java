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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.lyingslice.LyingFeatureSlice;
import org.sliceworkz.eventmodeling.mock.multicontext.MockContextFeatureSlice;
import org.sliceworkz.eventmodeling.mock.multicontext.OtherContext;

/**
 * Package scanning hands back a bare {@code Class} and the cast to {@code Slice<C>} is unchecked, so
 * nothing held a feature slice to the bounded context it declared: two contexts sharing a root package
 * each discovered the other's slices, ran their {@code configure...} methods against the wrong builder
 * and counted them in their own inventory. A slice now qualifies only where the context it declared can
 * accept the one being built.
 * <p>
 * Framework behaviour, not storage behaviour, so plain {@code @Test}s.
 */
public class FeatureSliceScanIsPerContextTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "ScanBoundedContext";

	@Test
	void aContextDeploysItsOwnSlicesAndTheOnesDeclaredOverASupertype ( ) {
		assertEquals(Set.of("MockContext", "AnyContext"), deployedSliceNamesOfTheMockContext());
	}

	/**
	 * The other context's slice registers a read model over event types this context never holds. Left
	 * to run, it would register it here — so this build succeeding is the whole point: without the
	 * filter it fails on that registration.
	 */
	@Test
	void aSliceOfAnotherContextIsNeitherConfiguredNorCounted ( ) {
		assertFalse(deployedSliceNamesOfTheMockContext().contains("OtherContext"));
	}

	/**
	 * Not an undeployed slice of this context either: a slice of another context is none of this
	 * context's business, where {@code disabledFeatures} means "this context's, switched off here".
	 */
	@Test
	void aSliceOfAnotherContextIsNotReportedAsUndeployedEither ( ) {
		BoundedContextEvent.BoundedContextStarting starting = startingOf(mockContextOverTheSharedPackage());
		assertTrue(sliceNames(starting.disabledFeatures()).isEmpty(),
				"expected no disabled slices, got: " + sliceNames(starting.disabledFeatures()));
	}

	/** The same package, scanned by the other context, yields the mirror image. */
	@Test
	void theOtherContextDeploysItsOwnSlicesOutOfTheSamePackage ( ) {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		BoundedContextBuilder<OtherContext> builder = BoundedContext.newBuilder(OtherContext.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.features().rootPackage(MockContextFeatureSlice.class.getPackage()).done();

		OtherContext context = builder.build();
		try {
			context.start();
			assertEquals(Set.of("OtherContext", "AnyContext"), sliceNames(startingOf(received).enabledFeatures()));
		} finally {
			context.terminate();
		}
	}

	/**
	 * The filter judges what a slice declared, and this slice declared the truth — it really is a
	 * {@code Slice<Mock>} — while registering another context's read model. So it is deployed, and the
	 * registration check is what names it. That check therefore has to run after the scan: run before,
	 * as it was, it never sees anything a slice registers, which is how components are normally
	 * registered at all.
	 */
	@Test
	void aComponentASliceRegistersIsCheckedToo ( ) {
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
		builder.features().rootPackage(LyingFeatureSlice.class.getPackage()).done();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
		assertTrue(e.getMessage().startsWith("component registered over another bounded context's event types: "
				+ "readmodel OtherContextReadModel (its domain event type is "), e.getMessage());
	}

	private Set<String> deployedSliceNamesOfTheMockContext ( ) {
		return sliceNames(startingOf(mockContextOverTheSharedPackage()).enabledFeatures());
	}

	private List<BoundedContextEvent> mockContextOverTheSharedPackage ( ) {
		List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());
		BoundedContextBuilder<Mock> builder = BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
		builder.features().rootPackage(MockContextFeatureSlice.class.getPackage()).done();
		buildBoundedContext(builder);
		return received;
	}

	private static BoundedContextEvent.BoundedContextStarting startingOf ( List<BoundedContextEvent> received ) {
		return received.stream()
				.filter(e -> e instanceof BoundedContextEvent.BoundedContextStarting)
				.map(e -> (BoundedContextEvent.BoundedContextStarting) e)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected a BoundedContextStarting event, got: " + received));
	}

	private static Set<String> sliceNames ( Set<BoundedContextEvent.FeatureSlice> slices ) {
		return slices.stream().map(BoundedContextEvent.FeatureSlice::name).collect(Collectors.toSet());
	}

}
